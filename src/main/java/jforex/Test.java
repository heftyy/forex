
package main.java.jforex;

import com.dukascopy.api.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;


/*
 * Created by VisualJForex Generator, version 1.22
 * Date: 12.07.2013 12:43
 */
public class Test implements IStrategy {

	private CopyOnWriteArrayList<TradeEventAction> tradeEventActions = new CopyOnWriteArrayList<TradeEventAction>();
	private static final String DATE_FORMAT_NOW = "yyyyMMdd_HHmmss";
	private IEngine engine;
	private IConsole console;
	private IHistory history;
	private IContext context;
	private IIndicators indicators;
	private IUserInterface userInterface;

	@Configurable("defaultSlippage:")
	public int defaultSlippage = 5;
	@Configurable("defaultTakeProfit:")
	public int defaultTakeProfit = 10;
	@Configurable("defaultPeriod:")
	public Period defaultPeriod = Period.ONE_HOUR;
	@Configurable("defaultTradeAmount:")
	public double defaultTradeAmount = 0.0010;
	@Configurable("defaultStopLoss:")
	public int defaultStopLoss = 10;
	@Configurable("defaultInstrument:")
	public Instrument defaultInstrument = Instrument.EURUSD;

	private String AccountCurrency = "";
	private double Leverage;
	private Tick LastTick =  null ;
	private String AccountId = "";
	private double Equity;
	private double UseofLeverage;
	private double EMA21_current_value;
	private double EMA55_previous_value;
	private List<IOrder> PendingPositions =  null ;
	private double EMA55_current_value;
	private Period tempVar168 = Period.ONE_HOUR;
	private List<IOrder> AllPositions =  null ;
	private double EMA21_previous_value;
	private double EMA89_current_value;
	private int OverWeekendEndLeverage;
	private int MarginCutLevel;
	private Candle LastAskCandle =  null ;
	private boolean GlobalAccount;
	private List<IOrder> OpenPositions =  null ;
	private IMessage LastTradeEvent =  null ;
	private Candle LastBidCandle =  null ;
	private double EMA89_previous_value;


	public void onStart(IContext context) throws JFException {
		this.engine = context.getEngine();
		this.console = context.getConsole();
		this.history = context.getHistory();
		this.context = context;
		this.indicators = context.getIndicators();
		this.userInterface = context.getUserInterface();

		subscriptionInstrumentCheck(defaultInstrument);

		ITick lastITick = context.getHistory().getLastTick(defaultInstrument);
		LastTick = new Tick(lastITick, defaultInstrument);

		IBar bidBar = context.getHistory().getBar(defaultInstrument, Period.ONE_MIN, OfferSide.BID, 1);
		IBar askBar = context.getHistory().getBar(defaultInstrument, Period.ONE_MIN, OfferSide.ASK, 1);
		LastAskCandle = new Candle(askBar, Period.ONE_MIN, defaultInstrument, OfferSide.ASK);
		LastBidCandle = new Candle(bidBar, Period.ONE_MIN, defaultInstrument, OfferSide.BID);

		if (indicators.getIndicator("EMA") == null) {
			indicators.registerDownloadableIndicator("1324","EMA");
		}
		if (indicators.getIndicator("EMA") == null) {
			indicators.registerDownloadableIndicator("1324","EMA");
		}
		if (indicators.getIndicator("EMA") == null) {
			indicators.registerDownloadableIndicator("1324","EMA");
		}
		if (indicators.getIndicator("EMA") == null) {
			indicators.registerDownloadableIndicator("1324","EMA");
		}
		subscriptionInstrumentCheck(Instrument.fromString("EUR/USD"));

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

	private void updateVariables() {
		try {
			AllPositions = engine.getOrders(defaultInstrument);
			List<IOrder> listMarket = new ArrayList<IOrder>();
			for (IOrder order: AllPositions) {
				if (order.getState().equals(IOrder.State.FILLED)){
					listMarket.add(order);
				}
			}
			List<IOrder> listPending = new ArrayList<IOrder>();
			for (IOrder order: AllPositions) {
				if (order.getState().equals(IOrder.State.OPENED)){
					listPending.add(order);
				}
			}
			OpenPositions = listMarket;
			PendingPositions = listPending;
		} catch(JFException e) {
			e.printStackTrace();
		}
	}

	public void onMessage(IMessage message) throws JFException {
		if (message.getOrder() != null && message.getOrder().getInstrument().equals(defaultInstrument)) {
			updateVariables();
			LastTradeEvent = message;
			for (TradeEventAction event :  tradeEventActions) {
				IOrder order = message.getOrder();
				if (order != null && event != null && message.getType().equals(event.getMessageType())&& order.getLabel().equals(event.getPositionLabel())) {
					Method method;
					try {
						method = this.getClass().getDeclaredMethod(event.getNextBlockId(), Integer.class);
						method.invoke(this, new Integer[] {event.getFlowId()});
					} catch (SecurityException e) {
						e.printStackTrace();
					} catch (NoSuchMethodException e) {
						e.printStackTrace();
					} catch (IllegalArgumentException e) {
						e.printStackTrace();
					} catch (IllegalAccessException e) {
						e.printStackTrace();
					} catch (InvocationTargetException e) {
						e.printStackTrace();
					}
					tradeEventActions.remove(event);
				}
			}
		}
	}

	public void onStop() throws JFException {
	}

	public void onTick(Instrument instrument, ITick tick) throws JFException {
		if (instrument.equals(defaultInstrument)) {
			LastTick = new Tick(tick, defaultInstrument);
			updateVariables();

		}
	}

	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
		if (instrument.equals(defaultInstrument) && period==defaultPeriod) {
			LastAskCandle = new Candle(askBar, period, instrument, OfferSide.ASK);
			LastBidCandle = new Candle(bidBar, period, instrument, OfferSide.BID);
			updateVariables();
			EMA_block_10(1);
		}
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

	public double round(double price, Instrument instrument) {
		BigDecimal big = new BigDecimal("" + price);
		big = big.setScale(instrument.getPipScale() + 1, BigDecimal.ROUND_HALF_UP);
		return big.doubleValue();
	}

	public ITick getLastTick(Instrument instrument) {
		try {
			return (context.getHistory().getTick(instrument, 0));
		} catch (JFException e) {
			e.printStackTrace();
		}
		return null;
	}

	private void EMA_block_10(Integer flow) {
		Instrument argument_1 = defaultInstrument;
		Period argument_2 = Period.ONE_HOUR;
		int argument_3 = 0;
		int argument_4 = 21;
		OfferSide[] offerside = new OfferSide[1];
		IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
		offerside[0] = OfferSide.BID;
		appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
		Object[] params = new Object[1];
		params[0] = 21;
		try {
			subscriptionInstrumentCheck(argument_1);
			long time = context.getHistory().getBar(argument_1, argument_2, OfferSide.BID, argument_3).getTime();
			Object[] indicatorResult = context.getIndicators().calculateIndicator(argument_1, argument_2, offerside,
					"EMA", appliedPrice, params, Filter.WEEKENDS, argument_3 + 1, time, 0);
			if ((new Double(((double [])indicatorResult[0])[0])) == null) {
				this.EMA21_current_value = Double.NaN;
			} else {
				this.EMA21_current_value = (((double [])indicatorResult[0])[0]);
			}
		} catch (JFException e) {
			e.printStackTrace();
		}
		EMA_block_12(flow);
	}

	private void EMA_block_12(Integer flow) {
		Instrument argument_1 = defaultInstrument;
		Period argument_2 = Period.ONE_HOUR;
		int argument_3 = 0;
		int argument_4 = 21;
		OfferSide[] offerside = new OfferSide[1];
		IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
		offerside[0] = OfferSide.BID;
		appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
		Object[] params = new Object[1];
		params[0] = 21;
		try {
			subscriptionInstrumentCheck(argument_1);
			long time = context.getHistory().getBar(argument_1, argument_2, OfferSide.BID, argument_3).getTime();
			Object[] indicatorResult = context.getIndicators().calculateIndicator(argument_1, argument_2, offerside,
					"EMA", appliedPrice, params, Filter.WEEKENDS, argument_3 + 1, time, 0);
			if ((new Double(((double [])indicatorResult[0])[0])) == null) {
				this.EMA21_previous_value = Double.NaN;
			} else {
				this.EMA21_previous_value = (((double [])indicatorResult[0])[0]);
			}
		} catch (JFException e) {
			e.printStackTrace();
		}
		EMA_block_15(flow);
	}

	private void EMA_block_15(Integer flow) {
		Instrument argument_1 = defaultInstrument;
		Period argument_2 = Period.ONE_HOUR;
		int argument_3 = 0;
		int argument_4 = 89;
		OfferSide[] offerside = new OfferSide[1];
		IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
		offerside[0] = OfferSide.BID;
		appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
		Object[] params = new Object[1];
		params[0] = 89;
		try {
			subscriptionInstrumentCheck(argument_1);
			long time = context.getHistory().getBar(argument_1, argument_2, OfferSide.BID, argument_3).getTime();
			Object[] indicatorResult = context.getIndicators().calculateIndicator(argument_1, argument_2, offerside,
					"EMA", appliedPrice, params, Filter.WEEKENDS, argument_3 + 1, time, 0);
			if ((new Double(((double [])indicatorResult[0])[0])) == null) {
				this.EMA89_current_value = Double.NaN;
			} else {
				this.EMA89_current_value = (((double [])indicatorResult[0])[0]);
			}
		} catch (JFException e) {
			e.printStackTrace();
		}
		EMA_block_16(flow);
	}

	private void EMA_block_16(Integer flow) {
		Instrument argument_1 = defaultInstrument;
		Period argument_2 = Period.ONE_HOUR;
		int argument_3 = 0;
		int argument_4 = 89;
		OfferSide[] offerside = new OfferSide[1];
		IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
		offerside[0] = OfferSide.BID;
		appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
		Object[] params = new Object[1];
		params[0] = 89;
		try {
			subscriptionInstrumentCheck(argument_1);
			long time = context.getHistory().getBar(argument_1, argument_2, OfferSide.BID, argument_3).getTime();
			Object[] indicatorResult = context.getIndicators().calculateIndicator(argument_1, argument_2, offerside,
					"EMA", appliedPrice, params, Filter.WEEKENDS, argument_3 + 1, time, 0);
			if ((new Double(((double [])indicatorResult[0])[0])) == null) {
				this.EMA89_previous_value = Double.NaN;
			} else {
				this.EMA89_previous_value = (((double [])indicatorResult[0])[0]);
			}
		} catch (JFException e) {
			e.printStackTrace();
		}
		If_block_17(flow);
	}

	private  void If_block_17(Integer flow) {
		double argument_1 = EMA21_current_value;
		double argument_2 = EMA21_previous_value;
		if (argument_1< argument_2) {
			If_block_19(flow);
		}
		else if (argument_1> argument_2) {
			If_block_18(flow);
		}
		else if (argument_1== argument_2) {
		}
	}

	private  void If_block_18(Integer flow) {
		double argument_1 = EMA89_current_value;
		double argument_2 = EMA89_previous_value;
		if (argument_1< argument_2) {
		}
		else if (argument_1> argument_2) {
			If_block_25(flow);
		}
		else if (argument_1== argument_2) {
		}
	}

	private  void If_block_19(Integer flow) {
		double argument_1 = EMA89_current_value;
		double argument_2 = EMA89_previous_value;
		if (argument_1< argument_2) {
			If_block_24(flow);
		}
		else if (argument_1> argument_2) {
		}
		else if (argument_1== argument_2) {
		}
	}

	private  void OpenatMarket_block_20(Integer flow) {
		Instrument argument_1 = defaultInstrument;
		double argument_2 = defaultTradeAmount;
		int argument_3 = defaultSlippage;
		int argument_4 = 10;
		int argument_5 = 10;
		String argument_6 = "";
		ITick tick = getLastTick(argument_1);

		IEngine.OrderCommand command = IEngine.OrderCommand.BUY;

		double stopLoss = tick.getBid() - argument_1.getPipValue() * argument_4;
		double takeProfit = tick.getBid() + argument_1.getPipValue() * argument_5;

		try {
			String label = getLabel();
			IOrder order = context.getEngine().submitOrder(label, argument_1, command, argument_2, 0, argument_3,  stopLoss, takeProfit, 0, argument_6);
		} catch (JFException e) {
			e.printStackTrace();
		}
	}

	private  void OpenatMarket_block_21(Integer flow) {
		Instrument argument_1 = defaultInstrument;
		double argument_2 = defaultTradeAmount;
		int argument_3 = defaultSlippage;
		int argument_4 = 10;
		int argument_5 = 10;
		String argument_6 = "";
		ITick tick = getLastTick(argument_1);

		IEngine.OrderCommand command = IEngine.OrderCommand.SELL;

		double stopLoss = tick.getAsk() + argument_1.getPipValue() * argument_4;
		double takeProfit = tick.getAsk() - argument_1.getPipValue() * argument_5;

		try {
			String label = getLabel();
			IOrder order = context.getEngine().submitOrder(label, argument_1, command, argument_2, 0, argument_3,  stopLoss, takeProfit, 0, argument_6);
		} catch (JFException e) {
			e.printStackTrace();
		}
	}

	private  void If_block_24(Integer flow) {
		int argument_1 = OpenPositions.size();
		int argument_2 = 0;
		if (argument_1< argument_2) {
		}
		else if (argument_1> argument_2) {
		}
		else if (argument_1== argument_2) {
			OpenatMarket_block_21(flow);
		}
	}

	private  void If_block_25(Integer flow) {
		int argument_1 = OpenPositions.size();
		int argument_2 = 0;
		if (argument_1< argument_2) {
		}
		else if (argument_1> argument_2) {
		}
		else if (argument_1== argument_2) {
			OpenatMarket_block_20(flow);
		}
	}

	class Candle  {

		IBar bar;
		Period period;
		Instrument instrument;
		OfferSide offerSide;

		public Candle(IBar bar, Period period, Instrument instrument, OfferSide offerSide) {
			this.bar = bar;
			this.period = period;
			this.instrument = instrument;
			this.offerSide = offerSide;
		}

		public Period getPeriod() {
			return period;
		}

		public void setPeriod(Period period) {
			this.period = period;
		}

		public Instrument getInstrument() {
			return instrument;
		}

		public void setInstrument(Instrument instrument) {
			this.instrument = instrument;
		}

		public OfferSide getOfferSide() {
			return offerSide;
		}

		public void setOfferSide(OfferSide offerSide) {
			this.offerSide = offerSide;
		}

		public IBar getBar() {
			return bar;
		}

		public void setBar(IBar bar) {
			this.bar = bar;
		}

		public long getTime() {
			return bar.getTime();
		}

		public double getOpen() {
			return bar.getOpen();
		}

		public double getClose() {
			return bar.getClose();
		}

		public double getLow() {
			return bar.getLow();
		}

		public double getHigh() {
			return bar.getHigh();
		}

		public double getVolume() {
			return bar.getVolume();
		}
	}
	class Tick {

		private ITick tick;
		private Instrument instrument;

		public Tick(ITick tick, Instrument instrument){
			this.instrument = instrument;
			this.tick = tick;
		}

		public Instrument getInstrument(){
			return  instrument;
		}

		public double getAsk(){
			return  tick.getAsk();
		}

		public double getBid(){
			return  tick.getBid();
		}

		public double getAskVolume(){
			return  tick.getAskVolume();
		}

		public double getBidVolume(){
			return tick.getBidVolume();
		}

		public long getTime(){
			return  tick.getTime();
		}

		public ITick getTick(){
			return  tick;
		}
	}

	public class AssertException extends RuntimeException {

		public AssertException(Object primary, Object compared) {
			super("Primary object : " + primary.toString() + " is different from " + compared.toString());
		}
	}
	protected String getLabel() {
		String label;
		label = "IVF" + getCurrentTime(LastTick.getTime()) + generateRandom(10000) + generateRandom(10000);
		return label;
	}

	private String getCurrentTime(long time) {
		SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_NOW);
		return sdf.format(time);
	}

	private static String generateRandom(int n) {
		int randomNumber = (int) (Math.random() * n);
		String answer = "" + randomNumber;
		if (answer.length() > 3) {
			answer = answer.substring(0, 4);
		}
		return answer;
	}

	class TradeEventAction {
		private IMessage.Type messageType;
		private String nextBlockId = "";
		private String positionLabel = "";
		private int flowId = 0;

		public IMessage.Type getMessageType() {
			return messageType;
		}

		public void setMessageType(IMessage.Type messageType) {
			this.messageType = messageType;
		}

		public String getNextBlockId() {
			return nextBlockId;
		}

		public void setNextBlockId(String nextBlockId) {
			this.nextBlockId = nextBlockId;
		}
		public String getPositionLabel() {
			return positionLabel;
		}

		public void setPositionLabel(String positionLabel) {
			this.positionLabel = positionLabel;
		}
		public int getFlowId() {
			return flowId;
		}
		public void setFlowId(int flowId) {
			this.flowId = flowId;
		}
	}
}

