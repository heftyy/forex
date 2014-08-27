package main.java.jforex;

import com.dukascopy.api.*;
import org.joda.time.DateTime;
import org.joda.time.LocalTime;

import java.math.BigDecimal;
import java.util.*;

@Library("JForex/Strategies/libs/joda-time-2.3.jar")
public class BoxBreakout implements IStrategy {

    public enum Direction { UP, DOWN }

    private class BreakoutBox {
        public double high;
        public double low;
        public boolean orderDone;
        public LocalTime start;
        public LocalTime end;

        public BreakoutBox(List<IBar> bars, LocalTime start, LocalTime end) {
            this.orderDone = false;
            this.start = start;
            this.end = end;
            high = 0;
            low = 0;
            for(IBar bar : bars) {
                if(bar.getLow() < low || low == 0) low = bar.getLow();
                if(bar.getHigh() > high || high == 0) high = bar.getHigh();
            }
        }
    }

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
    public Period period = Period.FIVE_MINS;
    @Configurable("TP")
    public int takeProfitPips = 30;
    @Configurable("SL")
    public int stopLossPips = 30;
    @Configurable("Trailing stop")
    public int trailingStop  = 15;
    @Configurable("Start breakout box")
    public String boxStartTimeInput = "06:00";
    @Configurable("Start breakout box")
    public String boxEndTimeInput = "08:55";
    @Configurable("Latest open time")
    public String lastOpenTimeInput = "12:00";

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

    private LocalTime boxStartTime;
    private LocalTime boxEndTime;
    private LocalTime lastOpenTime;
    private BreakoutBox breakoutBox;

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

        boxStartTime = LocalTime.parse(boxStartTimeInput);
        boxEndTime = LocalTime.parse(boxEndTimeInput);
        lastOpenTime = LocalTime.parse(lastOpenTimeInput);
        breakoutBox = null;

        subscriptionInstrumentCheck(instrument);

        this.console.getOut().println("Started");
    }

    public void onStop() throws JFException {
        closeAll();
        console.getOut().println("Stopped");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        int positions = positionsTotal(instrument);

        if(positions == 0) {
            if(breakoutBox == null) return;
            if(breakoutBox.orderDone == true) return;
            DateTime now = new DateTime(tick.getTime());
            if(now.getHourOfDay() > lastOpenTime.getHourOfDay()) return;
            if(now.getHourOfDay() == lastOpenTime.getHourOfDay() && now.getMinuteOfHour() > lastOpenTime.getMinuteOfHour()) return;
            double takeProfit, stopLoss;
            //long
            if(tick.getAsk() > breakoutBox.high) {
                takeProfit = tick.getAsk() + instrument.getPipValue() * takeProfitPips;
                stopLoss = tick.getAsk() - instrument.getPipValue() * stopLossPips;
                openOrder(instrument, IEngine.OrderCommand.BUY, lots, stopLoss, takeProfit);
                breakoutBox.orderDone = true;
            }
            //short
            if(tick.getBid() < breakoutBox.low) {
                takeProfit = tick.getBid() - instrument.getPipValue() * takeProfitPips;
                stopLoss = tick.getBid() + instrument.getPipValue() * stopLossPips;
                openOrder(instrument, IEngine.OrderCommand.SELL, lots, stopLoss, takeProfit);
                breakoutBox.orderDone = true;
            }
        }
        else if(positions > 0) {
            closePartially();
            trailingStop(tick);
        }
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (period == this.period)  {
            DateTime now = new DateTime(askBar.getTime());
            if(now.getHourOfDay() == boxEndTime.getHourOfDay() && now.getMinuteOfHour() == boxEndTime.getMinuteOfHour()) {
                DateTime startBars = now.minusHours(boxEndTime.getHourOfDay() - boxStartTime.getHourOfDay()).minusMinutes(boxEndTime.getMinuteOfHour() - boxStartTime.getMinuteOfHour());
                DateTime endBars = now;
                List<IBar> bars = history.getBars(instrument, period, OfferSide.BID, startBars.getMillis(), endBars.getMillis());
                breakoutBox = new BreakoutBox(bars, boxStartTime, boxEndTime);
            }
            if(now.getHourOfDay() == lastOpenTime.getHourOfDay()) {
                breakoutBox = null;
            }
            if(now.getHourOfDay() == boxStartTime.getHourOfDay() && now.getMinuteOfDay() == boxStartTime.getMinuteOfHour()) {
                closeAll();
            }
        }
    }

    protected void trailingStop(ITick tick) throws JFException {
        for(IOrder order : engine.getOrders(instrument)) {
            if(order.getState() == IOrder.State.FILLED) {
                if(trailingStop > 0) {
                    if(order.isLong()) {
                        if(tick.getAsk() - order.getOpenPrice() >= trailingStop * instrument.getPipValue()) {
                            if(order.getStopLossPrice() < (tick.getAsk() - trailingStop*instrument.getPipValue())) {
                                double tp = roundToPippette(tick.getAsk() + (takeProfitPips +trailingStop)*instrument.getPipValue(), instrument);
                                double sl = roundToPippette(tick.getAsk() - trailingStop*instrument.getPipValue(), instrument);
                                order.setStopLossPrice(sl);
                                order.setTakeProfitPrice(tp);
                            }
                        }
                    } else {
                        if(order.getOpenPrice() - tick.getBid() >= trailingStop*instrument.getPipValue()) {
                            if(order.getStopLossPrice() > (tick.getBid() + trailingStop*instrument.getPipValue())) {
                                double tp = roundToPippette(tick.getBid() - (takeProfitPips+trailingStop)*instrument.getPipValue(), instrument);
                                double sl = roundToPippette(tick.getBid() + trailingStop*instrument.getPipValue(), instrument);
                                order.setStopLossPrice(sl);
                                order.setTakeProfitPrice(tp);
                            }
                        }
                    }
                }
            }
        }
    }

    protected void setTakeProfitAll(Instrument instrument, double takeProfit) throws JFException {
        takeProfit = round(takeProfit, 5);
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getState() == IOrder.State.FILLED) {
                if (takeProfit != 0.0d && takeProfit != order.getTakeProfitPrice()) {
                    order.setTakeProfitPrice(takeProfit);
                }
            }
        }
    }

    protected void setStopLossAll(Instrument instrument, double stopLoss) throws JFException {
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

    private void closePartially() throws JFException {
        for(IOrder order : engine.getOrders()) {
            if(order.getState() == IOrder.State.FILLED) {
                if(order.getAmount() == lots && order.getProfitLossInPips() >= trailingStop) {
                    closeOrder(order, 0.5);
                }
            }
        }
    }

    protected void closeOrder(IOrder order, double part) throws JFException {
        double orderLot = order.getAmount();
        order.close(orderLot * part);
    }

    protected void closeOrder(IOrder order) throws JFException {
        order.close();
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
