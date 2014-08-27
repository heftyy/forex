package main.java.jforex;

import com.dukascopy.api.*;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;

public class PyramidStrategyOriginal implements IStrategy {

	private IEngine engine = null;
	private IIndicators indicators = null;
	private IHistory history = null;
	private int tagCounter = 0;
	private IConsole console;
	private IContext context;

	@Configurable("Maximum trades")
	public int maxTrades = 5;
	@Configurable("Starting lots")
	public double lots = 0.001;
	@Configurable("Multiplier")
	public double multiplier = 1.667;
	@Configurable("MACD fast")
	public int macdFast = 12;
	@Configurable("MACD slow")
	public int macdSlow = 26;
	@Configurable("MACD signal")
	public int macdSignal = 9;
	@Configurable("MACD shift")
	public int macdShift = 1;
	@Configurable("MACD hist test[0.0001]")
	public double macdHistTest = 2.5d;
	@Configurable("Moving avarage period")
	public int maPeriod = 33;
	@Configurable("Moving avarage shift")
	public int maShift = 1;
	@Configurable("Take profit in pips")
	public int takeProfitPips = 30;
	@Configurable("Stop loss in pips")
	public int stopLossPips = 120;
	@Configurable("Period")
	public Period period = Period.ONE_HOUR;
//	@Configurable("Trailing stop[pips]")
	public int trailingStop = 15;
//	@Configurable("Trailing stop diff[pips]")
	public int trailingStopDiff = 5;

	@Configurable("Next transaction open difference[pips]")
	public int transactionDifferenceOpen = 15;
	private int transactionDifferenceClose = 60;
	private int precision = 5;
	private int minTakeProfit = 5;

	private IEngine.OrderCommand currentCommand = null;
	private Instrument instrument = Instrument.EURUSD;
	private IBar bidBar = null, askBar = null;
	private ITick tick = null;

	public void onStart(IContext context) throws JFException {
		engine = context.getEngine();
		indicators = context.getIndicators();
		history = context.getHistory();
		macdHistTest *= 0.0001;
		this.console = context.getConsole();
		this.context = context;
		this.bidBar = context.getHistory().getBar(instrument, period, OfferSide.BID, 1);
		this.askBar = context.getHistory().getBar(instrument, period, OfferSide.ASK, 1);
		this.tick = context.getHistory().getTick(instrument, 1);
		subscriptionInstrumentCheck(instrument);
		console.getOut().println("Started");
	}

	public void onStop() throws JFException {
		closeAll();
		console.getOut().println("Stopped");
	}

	public void onTick(Instrument instrument, ITick tick) throws JFException {
		this.tick = tick;
	}

	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
		if (period == this.period) {
			//checkForClose(instrument);
			this.bidBar = bidBar;
			this.askBar = askBar;
			openTransaction();
			//trailingStop();
		}
	}

	protected void trailingStop() throws JFException {
		for(IOrder order : engine.getOrders(instrument)) {
			if(order.getState() == IOrder.State.FILLED) {
				if(trailingStop > 0) {
					if(order.isLong()) {
						if(tick.getAsk() - order.getOpenPrice() >= (trailingStop + trailingStopDiff)*instrument.getPipValue()) {
							if(order.getStopLossPrice() < (tick.getAsk() - trailingStop*instrument.getPipValue())) {
								double tp = roundToPippette(tick.getAsk() + (takeProfitPips+trailingStop)*instrument.getPipValue(), instrument);
								double sl = roundToPippette(tick.getAsk() - trailingStop*instrument.getPipValue(), instrument);
								order.setStopLossPrice(sl);
								order.setTakeProfitPrice(tp);
								print("modified trailing stop for buy");
							}
						}
					} else {
						if(order.getOpenPrice() - tick.getBid() >= (trailingStop+trailingStopDiff)*instrument.getPipValue()) {
							if(order.getStopLossPrice() > (tick.getBid() + trailingStop*instrument.getPipValue())) {
								double tp = roundToPippette(tick.getBid() - (takeProfitPips+trailingStop)*instrument.getPipValue(), instrument);
								double sl = roundToPippette(tick.getBid() + trailingStop*instrument.getPipValue(), instrument);
								order.setStopLossPrice(sl);
								order.setTakeProfitPrice(tp);
								print("modified trailing stop for sell");
							}
						}
					}
				}
			}
		}
	}

	protected void openTransaction() throws JFException {
		int openTrades = positionsTotal(instrument);
		boolean openNextOrder = false;

		if (openTrades == 0) {
			currentCommand = null;
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
				if (difference >= minDifference && difference <= maxDifference) {
					openNextOrder = true;
				} else if (difference > maxDifference) {
					print("CLOSE ALL AT BUY = "+new Date(askBar.getTime()));
					closeAll();
				}
			}

			if (currentCommand == IEngine.OrderCommand.SELL) {
				double difference = bidBar.getClose() - lastSellPrice;
				if (difference >= minDifference && difference <= maxDifference) {
					openNextOrder = true;
				} else if (difference > maxDifference) {
					print("CLOSE ALL AT SELL = "+new Date(bidBar.getTime()));
					closeAll();
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
			if(maPeriod > 0 && (macdSignal == 0 || macdFast == 0 || macdSlow == 0)) {
				currentCommand = checkForOpenMA(instrument);
			} else if(maPeriod == 0 && macdSignal > 0 && macdFast > 0 && macdSlow > 0) {
				currentCommand = checkForOpenMACD(instrument);
			}
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

	protected void setTakeProfit(Instrument instrument, double takeProfit) throws JFException {
		takeProfit = roundDouble(takeProfit, 5);
		for (IOrder order : engine.getOrders(instrument)) {
			if (order.getState() == IOrder.State.FILLED) {
				if (takeProfit != 0.0d && takeProfit != order.getTakeProfitPrice()) {
					order.setTakeProfitPrice(takeProfit);
				}
			}
		}
	}

	protected void setStopLoss(Instrument instrument, double stopLoss) throws JFException {
		stopLoss = roundDouble(stopLoss, 5);
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
				sumPrice += order.getOpenPrice() * order.getAmount();
				sumLot += order.getAmount();
			}
		}
		if ( positionsTotal(instrument) > 0 ) {
			sumPrice = roundDouble(sumPrice / sumLot, precision);
		}
		return sumPrice;
	}

	protected IOrder openOrder(Instrument instrument, IEngine.OrderCommand command, double lot) throws JFException {
		IOrder order = null;
		double takeProfitPrice = 0.0d, stopLossPrice = 0.0d;
		double[] tpsl = calculateTPSL(command);
		takeProfitPrice = roundToPippette(tpsl[0], instrument);
		stopLossPrice = roundToPippette(tpsl[1], instrument);
		if(command == IEngine.OrderCommand.BUY) {
			order = engine.submitOrder(getLabel(instrument), instrument, IEngine.OrderCommand.BUY, lot, 0, 5, stopLossPrice, takeProfitPrice);
		} else if(command == IEngine.OrderCommand.SELL) {
			order = engine.submitOrder(getLabel(instrument), instrument, IEngine.OrderCommand.SELL, lot, 0, 5, stopLossPrice, takeProfitPrice);
		}
		if ( order != null ) {
			int openTrades = positionsTotal(instrument);
			if (openTrades > 0) {
				//print("Setting up stop loss and take profit...");
				setTakeProfit(instrument, takeProfitPrice);
				setStopLoss(instrument, stopLossPrice);
			}
		}
		return order;
	}

	protected double[] calculateTPSL(IEngine.OrderCommand command) throws JFException {
		double tp = 0.0d, sl = 0.0d;
		double point = instrument.getPipValue();
		double breakEven = breakEven(instrument);
		if ( command == IEngine.OrderCommand.BUY ) {
			if(engine.getOrders(instrument).size() == 0) {
				double buyPrice = tick.getAsk();
				tp = buyPrice + takeProfitPips * point;
				sl = buyPrice - stopLossPips * point;
			} else {
				tp = breakEven + takeProfitPips * point;
				sl = engine.getOrders(instrument).get(0).getOpenPrice() - stopLossPips * point;
			}
		} else if ( command == IEngine.OrderCommand.SELL ) {
			if(engine.getOrders(instrument).size() == 0) {
				double sellPrice = tick.getBid();
				tp = sellPrice - takeProfitPips * point;
				sl = sellPrice + stopLossPips * point;
			} else {
				tp = breakEven - takeProfitPips * point;
				sl = engine.getOrders(instrument).get(0).getOpenPrice() + stopLossPips * point;
			}
		}
		double[] result = {tp, sl};
		return result;
	}

	protected IEngine.OrderCommand checkForOpenMACD(Instrument instrument) throws  JFException{
		double[] macd = indicators.macd(instrument, period, OfferSide.BID, IIndicators.AppliedPrice.CLOSE, macdFast, macdSlow, macdSignal, macdShift);
		double macdValue = macd[0];
		double macdSignal = macd[1];
		double macdHist = macd[2];
		if(macdHist > macdHistTest) {
			return IEngine.OrderCommand.BUY;
		}
		if (macdHist < -macdHistTest) {
			return IEngine.OrderCommand.SELL;
		}
		return null;
	}

	protected IEngine.OrderCommand checkForOpenMA(Instrument instrument) throws  JFException{
		double ma = indicators.sma(instrument, period, OfferSide.BID, IIndicators.AppliedPrice.CLOSE, maPeriod, maShift);
		IBar lastBar = history.getBar(instrument, period, OfferSide.BID, 1);
		double open = lastBar.getOpen();
		double close = lastBar.getClose();
		if (bidBar.getOpen() > ma && bidBar.getClose() < ma) {
			return IEngine.OrderCommand.SELL;
		}
		if (askBar.getOpen() < ma && askBar.getClose() > ma) {
			return IEngine.OrderCommand.BUY;
		}
		return null;
	}

	protected boolean checkForClose(Instrument instrument) throws  JFException{
		return false;
	}

	protected int countCreatedOrders(Instrument instrument) throws JFException {
		int count = 0;
		for(IOrder order : engine.getOrders(instrument)) {
			if(order.getState() == IOrder.State.CREATED) {
				count++;
			}
		}
		return count;
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
		double result = Double.valueOf(roundDouble(lots*Math.pow(multiplier, openPositions), 4) );
		return result;
	}

	protected double calculateProfit(Instrument instrument) throws JFException {
		double profit = 0.0d;
		for (IOrder order : engine.getOrders(instrument)) {
			if (order.getState() == IOrder.State.FILLED) {
				if (order.getOrderCommand() == IEngine.OrderCommand.BUY || order.getOrderCommand() == IEngine.OrderCommand.SELL) {
					profit += order.getProfitLossInAccountCurrency();
				}
			}
		}
		return profit;
	}

	private static double roundToPippette(double amount, Instrument instrument) {
		return round(amount, instrument.getPipScale() + 1);
	}

	private static double round(double amount, int decimalPlaces) {
		return (new BigDecimal(amount)).setScale(decimalPlaces, BigDecimal.ROUND_HALF_UP).doubleValue();
	}

	private void print(String format, Object...args) throws JFException {
		print(String.format(format,args));
	}

	private void print(Object o) throws  JFException {
		if(o instanceof Collection<?>) {
			Collection<?> col = (Collection<?>) o;
			for(Object obj : col) {
				print(obj);
			}
		}
		Calendar cal = Calendar.getInstance(TimeZone.getDefault());
		SimpleDateFormat sdf = new SimpleDateFormat("YYYY-MM-dd HH:mm:ss");
		cal.setTimeInMillis(history.getBar(instrument, period, OfferSide.BID, 0).getTime());
		cal.add(Calendar.HOUR, 0);
		console.getOut().println("["+sdf.format(cal.getTime())+"]"+o);
	}

	private void printErr(Object o){
		console.getErr().println(o);
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

	protected double roundDouble(double num, int dec) {
		return Math.round(num*Math.pow(10, dec))/Math.pow(10, dec);
	}


	protected String getLabel(Instrument instrument) {
		String label = instrument.name();
		label = label.substring(0, 2) + label.substring(3, 5);
		label = label + (tagCounter++);
		label = label.toLowerCase();
		return label;
	}

	public void onMessage(IMessage message) throws JFException {
		IOrder order = message.getOrder();
		if(order != null && order.getState() == IOrder.State.CLOSED) {
			if(message.getReasons().contains(IMessage.Reason.ORDER_CLOSED_BY_TP)) {
				closeAll();
			}
			if(message.getReasons().contains(IMessage.Reason.ORDER_CLOSED_BY_SL)) {
				closeAll();
			}
		}
	}

	public void onAccount(IAccount account) throws JFException {
		//System.out.println(account);
	}
}