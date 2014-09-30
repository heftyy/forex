package main.java.jforex;

import com.dukascopy.api.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NotNLA implements IStrategy {

    public enum Direction { UP, DOWN }

    private IEngine engine = null;
    private IIndicators indicators = null;
    private IHistory history = null;
    private int tagCounter = 0;
    private IConsole console;
    private IChart chart;
    private IContext context = null;

    @Configurable("Lot")
    public double lots = 0.5;
    @Configurable("Slippage")
    public int slippage = 5;
    @Configurable("Period")
    public Period period = Period.ONE_HOUR;
    @Configurable("TP max")
    public int takeProfitMaxPips = 30;
    @Configurable("TP min")
    public int takeProfitMinPips = 10;
    @Configurable("SL")
    public int stopLossPips = 20;
    @Configurable("")
    public Set<Period> periods = new HashSet<Period>(
        Arrays.asList(new Period[]{Period.DAILY})
    );

    private int precision = 5;
    private Instrument instrument = Instrument.EURUSD;

    private String AccountCurrency = "";
    private double Leverage;
    private String AccountId = "";
    private double Equity;
    private double UseofLeverage;
    private int OverWeekendEndLeverage;
    private int MarginCutLevel;
    private boolean GlobalAccount;

    public void onStart(IContext context) throws JFException {
        this.context = context;
        this.engine = context.getEngine();
        this.indicators = context.getIndicators();
        this.history = context.getHistory();
        this.console = context.getConsole();
        this.chart = context.getChart(Instrument.EURUSD);

        /*
        this.bidBar = context.getHistory().getBar(instrument, period, OfferSide.BID, 1);
        this.askBar = context.getHistory().getBar(instrument, period, OfferSide.ASK, 1);
        this.lastTick = context.getHistory().getLastTick(instrument);
        */

        subscriptionInstrumentCheck(instrument);

        this.console.getOut().println("Started");
    }

    public void onStop() throws JFException {
        closeAll();
        console.getOut().println("Stopped");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        System.out.println(tick.getAsk());
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (period == this.period)  {
            IBar lastBar = context.getHistory().getBar(instrument, period, OfferSide.ASK, 2);
            //LONG
            if(checkBars(lastBar, askBar) == IEngine.OrderCommand.BUY) {
                boolean trendMatch = true;
                for(Period p : periods) {
                    if(getBarDirection(context.getHistory().getBar(instrument, p, OfferSide.ASK, 1)) == Direction.DOWN) {
                        trendMatch = false;
                    }
                }

                double takeProfit, stopLoss;
                double price = history.getLastTick(instrument).getAsk();
                if(trendMatch) {
                    takeProfit = price + instrument.getPipValue() * takeProfitMaxPips;
                    stopLoss = price - instrument.getPipValue() * stopLossPips;
                }
                else {
                    takeProfit = price + instrument.getPipValue() * takeProfitMinPips;
                    stopLoss = price - instrument.getPipValue() * stopLossPips;
                }
                openOrder(instrument, IEngine.OrderCommand.BUY, lots, stopLoss, takeProfit);
            }

            //SHORT
            if(checkBars(lastBar, askBar) == IEngine.OrderCommand.SELL) {
                boolean trendMatch = true;
                for(Period p : periods) {
                    if(getBarDirection(context.getHistory().getBar(instrument, p, OfferSide.BID, 1)) == Direction.UP) {
                        trendMatch = false;
                    }
                }
                double takeProfit, stopLoss;
                double price = history.getLastTick(instrument).getAsk();
                if(trendMatch) {
                    takeProfit = price - instrument.getPipValue() * takeProfitMaxPips;
                    stopLoss = price + instrument.getPipValue() * stopLossPips;
                }
                else {
                    takeProfit = price - instrument.getPipValue() * takeProfitMinPips;
                    stopLoss = price + instrument.getPipValue() * stopLossPips;
                }
                openOrder(instrument, IEngine.OrderCommand.SELL, lots, stopLoss, takeProfit);
            }
        }
    }

    private IEngine.OrderCommand checkBars(IBar lastBar, IBar bar) throws JFException {
        IEngine.OrderCommand result;
        result = checkBarsInverse(lastBar, bar);
        if(result != null) return result;

        /*
        result = checkBarsSequence(bar);
        if(result != null) return result;
        */

        return null;
    }

    private IEngine.OrderCommand checkBarsInverse(IBar lastBar, IBar bar) {
        //LONG
        if(getBarDirection(lastBar) == Direction.DOWN && getBarDirection(bar) == Direction.UP && bar.getClose() > lastBar.getOpen() ) {
            return IEngine.OrderCommand.BUY;
        }
        //SHORT
        if(getBarDirection(lastBar) == Direction.UP && getBarDirection(bar) == Direction.DOWN && bar.getClose() < lastBar.getOpen()) {
            return IEngine.OrderCommand.SELL;
        }
        return null;
    }

    private IEngine.OrderCommand checkBarsSequence(IBar bar) throws JFException {
        for(int i = 1;  ; i++) {
            IBar previousBar = history.getBar(instrument, period, OfferSide.ASK, i);
            if(previousBar.equals(bar)) break;
            if(getBarDirection(previousBar) == null || getBarDirection(bar) == null) break;

            if(getBarDirection(previousBar) == getBarDirection(bar)) {
                continue;
            }
            else {
                //LONG
                if(getBarDirection(bar) == Direction.UP && bar.getClose() > previousBar.getOpen()) {
                    return IEngine.OrderCommand.BUY;
                }
                //SHORT
                if(getBarDirection(bar) == Direction.DOWN && bar.getClose() < previousBar.getOpen()) {
                    return IEngine.OrderCommand.SELL;
                }
                break;
            }
        }
        return null;
    }

    private Direction getBarDirection(IBar bar) {
        if(bar.getOpen() == 0 || bar.getClose() == 0) {
            return null;
        }
        else if(bar.getOpen() < bar.getClose()) {
            return Direction.UP;
        }
        else if(bar.getOpen() > bar.getClose()) {
            return Direction.DOWN;
        }
        return null;
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

    protected IOrder openOrder(Instrument instrument, IEngine.OrderCommand command, double lot) throws JFException {
        return openOrder(instrument, command, lot, 0, 0);
    }

    protected IOrder openOrder(Instrument instrument, IEngine.OrderCommand command, double lot, double stopLoss, double takeProfit) throws JFException {
        IOrder order = null;
        if ( command == IEngine.OrderCommand.BUY ) {
            double lastAskPrice = history.getLastTick(instrument).getAsk();
            order = engine.submitOrder(getLabel(instrument), instrument, IEngine.OrderCommand.BUY, lot, lastAskPrice, slippage, stopLoss, takeProfit);
        } else if ( command == IEngine.OrderCommand.SELL ) {
            double lastBidPrice = history.getLastTick(instrument).getBid();
            order = engine.submitOrder(getLabel(instrument), instrument, IEngine.OrderCommand.SELL, lot, lastBidPrice, slippage, stopLoss, takeProfit);
        }
        if ( order != null ) {
            System.out.println("ORDER PLACED FOR = "+lot*1000000/100+" EUR");
        }
        return order;
    }

    protected int positionsTotal(Instrument instrument) throws JFException {
        int counter = 0;
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getState() == IOrder.State.FILLED) {
                counter++;
            }
        }
        return counter;
    }

    protected double calculateProfit(Instrument instrument, List<IOrder> orders) throws JFException {
        double profit = 0.0d;
        double lot = 0.0d;
        for (IOrder order : orders) {
            if (order.getState() == IOrder.State.FILLED && order.getOrderCommand() == IEngine.OrderCommand.BUY || order.getOrderCommand() == IEngine.OrderCommand.SELL) {
                profit += order.getProfitLossInAccountCurrency() * order.getAmount();
                lot += order.getAmount();
            }
        }
        if ( lot != 0.0d ) {
            profit = round(profit/ lot, precision);
        }
        return profit;
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
        if(message.getType() == IMessage.Type.ORDER_FILL_OK) {

        }
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
