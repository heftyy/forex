package main.java.jforex;

import com.dukascopy.api.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PyramidStrategy implements IStrategy {

    private IEngine engine = null;
    private IIndicators indicators = null;
    private IHistory history = null;
    private int tagCounter = 0;
    private IConsole console;
    private IChart chart;
	private IContext context = null;

    @Configurable("Maximum trades")
    public int maxTrades = 5;
    @Configurable("Starting lots")
    public double lots = 0.001;
    @Configurable("Multiplier")
    public double multiplier = 1.667;
    @Configurable("Moving avarage period")
    public int maPeriod = 22;
    @Configurable("Moving avarage shift")
    public int maShift = 1;
    @Configurable("Take profit in pips")
    public int takeProfitPips = 20;
    @Configurable("Stop loss in pips")
    public int stopLossPips = 120;
    @Configurable("Period")
    public Period period = Period.FIVE_MINS;
    @Configurable("BBands period")
    public int bbandsPeriod = 100;
    @Configurable("BBands nbDevUp")
    public double bbandsNbDevUp = 2.0d;
    @Configurable("BBands nbDevDown")
    public double bbandsNbDevDown = 2.0d;

    private int transactionDifferenceOpen = 5;
    private int transactionDifferenceClose = 20;
    private int precision = 5;
    private int minTakeProfit = 5;

	private String AccountCurrency = "";
	private double Leverage;
	private String AccountId = "";
	private double Equity;
	private double UseofLeverage;
	private int OverWeekendEndLeverage;
	private int MarginCutLevel;
	private boolean GlobalAccount;

    private double breakEven = 0.0d;
    private IEngine.OrderCommand currentCommand = null;
	private IBar bidBar = null, askBar = null;
	private ITick lastTick = null;
    private Instrument instrument = Instrument.EURUSD;

    public void onStart(IContext context) throws JFException {
	    this.context = context;
        this.engine = context.getEngine();
	    this.indicators = context.getIndicators();
	    this.history = context.getHistory();
	    this.console = context.getConsole();
	    this.chart = context.getChart(Instrument.EURUSD);

	    this.bidBar = context.getHistory().getBar(instrument, period, OfferSide.BID, 1);
	    this.askBar = context.getHistory().getBar(instrument, period, OfferSide.ASK, 1);
	    this.lastTick = context.getHistory().getLastTick(instrument);

	    if (this.indicators.getIndicator("BBANDS") == null) {
		    this.indicators.registerDownloadableIndicator("1304","BBANDS");
	    }
	    subscriptionInstrumentCheck(instrument);

	    this.console.getOut().println("Started");
    }

    public void onStop() throws JFException {
        closeAll();
        console.getOut().println("Stopped");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        /*
        int openTrades = positionsTotal(instrument);
        if (openTrades > (maxTrades/2+1)) {
            double profit = calculateProfit(instrument);
                   if (profit >= minTakeProfit) {
                if(currentCommand == IEngine.OrderCommand.BUY) {
                    setStopLoss(instrument, breakEven+(minTakeProfit*instrument.getPipValue()));
                } else if(currentCommand == IEngine.OrderCommand.SELL) {
                    setStopLoss(instrument, breakEven-(minTakeProfit*instrument.getPipValue()));
                }
            }
        }
        */
        /*
        if (openTrades < (maxTrades/2+1) || breakEven == 0.0d) return;
        if (currentCommand == IEngine.OrderCommand.BUY) {
            double difference = tick.getBid() - breakEven;
            if (difference >= minTakeProfit*instrument.getPipValue()) {
                closeAll();
            }
        } else  if (currentCommand == IEngine.OrderCommand.SELL) {
            double difference = breakEven - tick.getAsk();
            if (difference > minTakeProfit*instrument.getPipValue()) {
                closeAll();
            }
        }
        */
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (period == this.period)  {
	        double[] bbands = indicators.bbands(instrument, period, OfferSide.BID, IIndicators.AppliedPrice.CLOSE, bbandsPeriod, bbandsNbDevUp, bbandsNbDevDown, IIndicators.MaType.EMA, 1);
	        this.bidBar = bidBar;
	        this.askBar = askBar;
            checkForClose(instrument, bbands);
            openTransaction(instrument, bbands);
//            setupStopLossAndTakeProfit(instrument, positionsTotal(instrument));
        }
    }

    protected void openTransaction(Instrument instrument, double[] bbands) throws JFException {
        int openTrades = positionsTotal(instrument);
        boolean openNextOrder = false;

        if (openTrades == 0) {
            currentCommand = null;
            breakEven = 0.0d;
        }

        if (openTrades == maxTrades) {
            return;
        }

        if (openTrades > 0 && openTrades < maxTrades) {
            double lastBuyPrice = findLastOrderPrice(instrument, IEngine.OrderCommand.BUY);
            double lastSellPrice = findLastOrderPrice(instrument, IEngine.OrderCommand.SELL);

            double minDifference = transactionDifferenceOpen * instrument.getPipValue();
            double maxDifference = transactionDifferenceClose * instrument.getPipValue();
            if (currentCommand == IEngine.OrderCommand.BUY) {
                double difference = lastBuyPrice - askBar.getClose();
                if (difference >= minDifference) {
                    openNextOrder = true;
                }
            }

            if (currentCommand == IEngine.OrderCommand.SELL) {
                double difference = bidBar.getClose() - lastSellPrice;
                if (difference >= minDifference) {
                    openNextOrder = true;
                }
            }
        }
        if (openNextOrder) {
            double nextLot = getLots(instrument);
            if (currentCommand == IEngine.OrderCommand.BUY) {
                if (nextLot > 0.0) {
                    IOrder order = openOrder(instrument, currentCommand, nextLot);
                    if (order == null) {
                        System.out.println("Error when filling an order" + currentCommand + " LOT = " +nextLot);
                    }
                }
            } else if (currentCommand == IEngine.OrderCommand.SELL) {
                if (nextLot > 0.0) {
                    IOrder order = openOrder(instrument, currentCommand, nextLot);
                    if (order == null) {
                        System.out.println("Error when filling an order" + currentCommand + " LOT = " +nextLot);
                    }
                }
            }
        }
        if (openTrades == 0) {
            currentCommand = checkForOpen(instrument, bbands);
            double nextLot = getLots(instrument);
            if (currentCommand == IEngine.OrderCommand.BUY) {
                if (nextLot > 0.0) {
                    IOrder order = openOrder(instrument, currentCommand, nextLot);
                    if (order == null) {
                        System.out.println("Error when filling an order" + currentCommand + " LOT = " + nextLot);
                    }
                }
            } else if (currentCommand == IEngine.OrderCommand.SELL) {
                if (nextLot > 0.0) {
                    IOrder order = openOrder(instrument, currentCommand, nextLot);
                    if (order == null) {
                        System.out.println("Error when filling an order" + currentCommand + " LOT = " +nextLot);
                    }
                }
            }
        }
    }

    protected void setupStopLossAndTakeProfit(Instrument instrument, int openTrades) throws JFException {
        double point = instrument.getPipValue();
        breakEven = breakEven(instrument);
        double takeProfitPrice = 0.0d, stopLossPrice = 0.0d;
        if (openTrades > 0) {
            if (currentCommand == IEngine.OrderCommand.BUY) {
                takeProfitPrice = breakEven + (takeProfitPips - calculateProfit(instrument, engine.getOrders(instrument))) * point;
                stopLossPrice = engine.getOrders(instrument).get(0).getOpenPrice() - stopLossPips * point;
            } else if (currentCommand == IEngine.OrderCommand.SELL) {
                takeProfitPrice = breakEven - takeProfitPips * point;
                stopLossPrice = engine.getOrders(instrument).get(0).getOpenPrice() + stopLossPips * point;
            }
            setTakeProfit(instrument, takeProfitPrice);
            setStopLoss(instrument, stopLossPrice);
        }
    }

    protected void setTakeProfit(Instrument instrument, double takeProfit) throws JFException {
        takeProfit = round(takeProfit, 5);
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getState() == IOrder.State.FILLED) {
                if (takeProfit != 0.0d && takeProfit != order.getTakeProfitPrice()) {
                    order.setTakeProfitPrice(takeProfit);
                }
            }
        }
    }

    protected void setStopLoss(Instrument instrument, double stopLoss) throws JFException {
        stopLoss = round(stopLoss, 5);
        for (IOrder order : engine.getOrders(instrument)) {
        	if (order.getState() == IOrder.State.FILLED) {
                if (stopLoss != 0.0d && stopLoss != order.getStopLossPrice()) {
                    order.setStopLossPrice(stopLoss);
                }
            }
        }
    }

    protected void closeAll() throws JFException {
        for (IOrder order : engine.getOrders()) {
            order.close();
        }
    }

    protected double breakEven(Instrument instrument) throws JFException {
        double sumPrice = 0;
        double sumLot = 0;
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getState() == IOrder.State.FILLED) {
                sumPrice += order.getOpenPrice() * order.getOriginalAmount();
                sumLot += order.getOriginalAmount();
            }
        }
        if ( positionsTotal(instrument) > 0 ) {
            sumPrice = round(sumPrice / sumLot, precision);
        }
        return sumPrice;
    }

    protected IOrder openOrder(Instrument instrument, IEngine.OrderCommand command, double lot) throws JFException {
        IOrder order = null;
        if ( command == IEngine.OrderCommand.BUY ) {
            order = engine.submitOrder(getLabel(instrument), instrument, IEngine.OrderCommand.BUY, lot, 0, 0);
        } else if ( command == IEngine.OrderCommand.SELL ) {
            order = engine.submitOrder(getLabel(instrument), instrument, IEngine.OrderCommand.SELL, lot, 0, 0);
        }
        if ( order != null ) {
            System.out.println("ORDER PLACED FOR = "+lot*1000000/100+" EUR");
        }
        return order;
    }

    protected IEngine.OrderCommand checkForOpen(Instrument instrument, double[] bbands) throws  JFException{
	    double upper = bbands[0];
	    double middle = bbands[1];
	    double lower = bbands[2];
	    if( askBar.getClose() < lower ) {
		    return IEngine.OrderCommand.BUY;
	    }
	    if( bidBar.getClose() > upper ) {
		    return IEngine.OrderCommand.SELL;
	    }
	    return null;
    }

    protected void checkForClose(Instrument instrument, double[] bbands) throws  JFException{
        double upper = bbands[0];
	    double middle = bbands[1];
        double lower = bbands[2];
        for(IOrder order : engine.getOrders(instrument)) {
            if(order.getState() == IOrder.State.FILLED || order.getState() == IOrder.State.OPENED) {
                if(order.isLong()) {
	                double close = askBar.getClose();
                    if(close > middle) {
                        closeAll();
                    }
                }
                else {
	                double close = bidBar.getClose();
                    if(close < middle) {
                        closeAll();
                    }
                }
            }
        }
    }

    //count open positions
    protected int positionsTotal(Instrument instrument) throws JFException {
        int counter = 0;
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getState() == IOrder.State.FILLED) {
                counter++;
            }
        }
        return counter;
    }

    protected double getLots(Instrument instrument) throws JFException {
        int openPositions = positionsTotal(instrument);
        double result = Double.valueOf(round(lots * Math.pow(multiplier, openPositions), 3) );
        return result;
    }

    protected double calculateProfit(Instrument instrument, List<IOrder> orders) throws JFException {
        double profit = 0.0d;
        double lot = 0.0d;
        for (IOrder order : orders) {
            if (order.getOrderCommand() == IEngine.OrderCommand.BUY || order.getOrderCommand() == IEngine.OrderCommand.SELL) {
                profit += order.getProfitLossInAccountCurrency() * order.getAmount();
                lot += order.getAmount();
            }
        }
        if ( lot != 0.0d ) {
            profit = round(profit/ lot, precision);
        }
        return profit;
    }

    protected double findLastOrderPrice(Instrument instrument, IEngine.OrderCommand command) throws JFException {
        double openPrice = 0.0d;
        long lastOrderTime = 0;
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getState() == IOrder.State.FILLED && order.getOrderCommand() == command) {
                if (order.getFillTime() > lastOrderTime) {
                    openPrice = order.getOpenPrice();
                    lastOrderTime = order.getFillTime();
                }
            }
        }
        return openPrice;
    }

    private static double roundToPippette(double amount, Instrument instrument) {
        return round(amount, instrument.getPipScale() + 1);
    }

    private static double round(double amount, int decimalPlaces) {
        return (new BigDecimal(amount)).setScale(decimalPlaces, BigDecimal.ROUND_HALF_UP).doubleValue();
    }

    protected void printErr(String err) {
        console.getErr().println(err);
    }

	public void subscriptionInstrumentCheck(Instrument instrument) {
		try {
			if (!context.getSubscribedInstruments().contains(instrument)) {
				Set<Instrument> instruments = new HashSet<Instrument>();
				instruments.add(instrument);
				context.setSubscribedInstruments(instruments, true);
				Thread.sleep(100);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

    protected String getLabel(Instrument instrument) {
        String label = instrument.name();
        label = label.substring(0, 2) + label.substring(3, 5);
        label = label + (tagCounter++);
        label = label.toLowerCase();
        return label;
    }

    public void onMessage(IMessage message) throws JFException {
        //System.out.println(message);
    }

	public void onAccount(IAccount account) throws JFException {
		AccountCurrency = account.getCurrency().toString();
		Leverage = account.getLeverage();
		AccountId= account.getAccountId();
		Equity = account.getEquity();
		UseofLeverage = account.getUseOfLeverage();
		OverWeekendEndLeverage = account.getOverWeekEndLeverage();
		MarginCutLevel = account.getMarginCutLevel();
		GlobalAccount = account.isGlobal();
	}
}