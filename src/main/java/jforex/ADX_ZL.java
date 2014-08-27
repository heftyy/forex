package main.java.jforex;

import com.dukascopy.api.*;
import com.dukascopy.api.drawings.IChartObjectFactory;
import com.dukascopy.api.drawings.IShortLineChartObject;
import org.joda.time.DateTime;
import org.joda.time.LocalTime;

import java.awt.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;

@Library("JForex/Strategies/libs/joda-time-2.3.jar")
public class ADX_ZL implements IStrategy {

    public enum Direction { UP, DOWN }

    private static class ZeroLine {
        protected double price;
        protected IEngine.OrderCommand command;
        protected Period period;
        protected DateTime time;
        private UUID uuid;
        private IShortLineChartObject shortLine;
        private IChart chart;
        private IChartObjectFactory factory;

        ZeroLine(double price, IEngine.OrderCommand cmd, Period period, long time, IChart chart) {
            this.price = price;
            this.command = cmd;
            this.period = period;
            this.time = new DateTime(time);
            this.uuid = UUID.randomUUID();
            this.chart = chart;
            this.factory = chart.getChartObjectFactory();
        }

        private void endLine(long endTime) {
            shortLine.setTime(1, endTime);
            shortLine.setColor(Color.LIGHT_GRAY);
            if(this.period == Period.FOUR_HOURS) shortLine.setColor(Color.CYAN);
//            shortLine.setText("");
//            chart.remove(shortLine);
        }

        protected static void removeOldLines(List<ZeroLine> zeroLines, IBar bar, Period period) {
            for (Iterator<ZeroLine> it = zeroLines.iterator(); it.hasNext();) {
                ZeroLine zl = it.next();
                if(period == zl.period) {
                    if(bar.getOpen() > zl.price && bar.getClose() < zl.price) {
                        zl.endLine(bar.getTime());
                        it.remove();
                        continue;
                    }
                    if(bar.getOpen() < zl.price && bar.getClose() > zl.price) {
                        zl.endLine(bar.getTime());
                        it.remove();
                        continue;
                    }
                }
            }
        }

        private void render() {
            shortLine = factory.createShortLine("zl_"+uuid.toString(),
                    time.getMillis(), price,
                    System.currentTimeMillis(), price);
//            shortLine.setText("ZL "+this.command+" "+this.period);
            if(this.command == IEngine.OrderCommand.BUY) shortLine.setColor(Color.BLACK);
            if(this.command == IEngine.OrderCommand.SELL) shortLine.setColor(Color.RED);
            if(this.period == Period.FOUR_HOURS) shortLine.setColor(Color.CYAN);
            chart.add(shortLine);
        }

        @Override
        public String toString() {
            return this.price+"\t"+this.period+"\t"+this.command+"\t"+this.time;
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
    @Configurable("Period open")
    public Period periodOpen = Period.FIFTEEN_MINS;
    @Configurable("Period close")
    public Period periodClose = Period.FOUR_HOURS;
    @Configurable("TP[pips]")
    public int takeProfitPips = 30;
    @Configurable("SL[pips]")
    public int stopLossPips = 30;
    @Configurable("Trailing stop[pips]")
    public int trailingStop  = 15;
    @Configurable("Open tolerance[pips]")
    public int openTolerance = 5;
    @Configurable("Open time start")
    public String openTimeStartString = "8:00";
    @Configurable("Open time end")
    public String openTimeEndString = "14:00";

    public int adx_period = 14;

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
    private List<ZeroLine> zeroLines;

    private LocalTime openTimeStart;
    private LocalTime openTimeEnd;

    public void onStart(IContext context) throws JFException {
        this.context = context;
        this.engine = context.getEngine();
        this.indicators = context.getIndicators();
        this.history = context.getHistory();
        this.console = context.getConsole();
        this.chart = context.getChart(Instrument.EURUSD);
        this.zeroLines = new ArrayList<ZeroLine>();
        /*
        this.bidBar = context.getHistory().getBar(instrument, period, OfferSide.BID, 1);
        this.askBar = context.getHistory().getBar(instrument, period, OfferSide.ASK, 1);
        this.lastTick = context.getHistory().getLastTick(instrument);
        */

        openTimeStart = LocalTime.parse(openTimeStartString);
        openTimeEnd = LocalTime.parse(openTimeEndString);

        long prevBarTime = history.getPreviousBarStart(this.periodOpen, history.getLastTick(Instrument.EURUSD).getTime());
        List<IBar> barsOpenPeriod = history.getBars(Instrument.EURUSD, this.periodOpen, OfferSide.BID, Filter.NO_FILTER, 100, prevBarTime, 0);
        for(IBar bar : barsOpenPeriod) {
            manageZeroLines(this.periodOpen, bar);
        }

        prevBarTime = history.getPreviousBarStart(this.periodClose, history.getLastTick(Instrument.EURUSD).getTime());
        List<IBar> barsClosePeriod = history.getBars(Instrument.EURUSD, this.periodClose, OfferSide.BID, Filter.NO_FILTER, 100, prevBarTime, 0);
        for(IBar bar : barsClosePeriod) {
            manageZeroLines(this.periodClose, bar);
        }

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
        }
        else if(positions > 0) {
            trailingStop(tick);
        }
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if(period == this.periodOpen) {
//            manageZeroLines(periodOpen, bidBar);

            DateTime now = new DateTime(bidBar.getTime());
            if(now.getHourOfDay() < openTimeStart.getHourOfDay()) return;
            if(now.getHourOfDay() > openTimeEnd.getHourOfDay()) return;

            if(positionsTotal(instrument) == 0) {
                IBar hourBar = history.getBar(instrument, Period.ONE_HOUR, OfferSide.BID, 0);
                IBar dailyBar = history.getBar(instrument, Period.DAILY, OfferSide.BID, 0);
                //LONG
                if(getBarDirection(bidBar) == Direction.DOWN && getBarDirection(hourBar) == Direction.UP && getBarDirection(dailyBar) == Direction.UP) {

                    /*
                    double adx_last = indicators.adx(instrument, periodOpen, OfferSide.BID, adxPeriod, 1);
                    double adx_current = iADX(NULL, 0, adxPeriod, PRICE_CLOSE, MODE_MAIN, 1);
                    double adxdi_plus_last = iADX(NULL, 0, adxPeriod, PRICE_CLOSE, MODE_PLUSDI, 2);
                    double adxdi_plus_current = iADX(NULL, 0, adxPeriod, PRICE_CLOSE, MODE_PLUSDI, 1);
                    double adxdi_minus_last = iADX(NULL, 0, adxPeriod, PRICE_CLOSE, MODE_MINUSDI, 2);
                    double adxdi_minus_current = iADX(NULL, 0, adxPeriod, PRICE_CLOSE, MODE_MINUSDI, 1);

                    if() {
                        double takeProfit, stopLoss;
                        ZeroLine buyZL = findClosestZL(bidBar.getClose(), periodClose, IEngine.OrderCommand.BUY);
                        ZeroLine sellZL = findClosestZL(bidBar.getClose(), periodClose, IEngine.OrderCommand.SELL);
                        takeProfit = buyZL.price - 5 * instrument.getPipValue();
                        stopLoss = sellZL.price + 5 * instrument.getPipValue();
                    }
                    */
                }
            }
        }
        if(period == this.periodClose) {
            manageZeroLines(periodClose, bidBar);
        }
    }

    private ZeroLine findClosestZL(double price, Period period, IEngine.OrderCommand cmd) {
        ZeroLine result = null;
        for(ZeroLine zl : zeroLines) {
            if(period != zl.period) continue;
            if(cmd == IEngine.OrderCommand.BUY) {
                if(zl.price > price && result == null) result = zl;
                if(zl.price > price && zl.price < result.price) result = zl;
            }
            else if(cmd == IEngine.OrderCommand.SELL) {
                if(zl.price < price && result == null) result = zl;
                if(zl.price < price && zl.price > result.price) result = zl;
            }
        }
        return result;
    }

    protected void trailingStop(ITick tick) throws JFException {
        for(IOrder order : engine.getOrders(instrument)) {
            if(order.getState() == IOrder.State.FILLED) {
                if(trailingStop > 0) {
                    if(order.isLong()) {
                        if(order.getProfitLossInPips() > trailingStop) {
                            closePartially(order);
                            if(order.getStopLossPrice() < (tick.getAsk() - trailingStop*instrument.getPipValue())) {
                                //double tp = roundToPippette(tick.getAsk() + (takeProfitPips +trailingStop)*instrument.getPipValue(), instrument);
                                double sl = roundToPippette(tick.getAsk() - trailingStop*instrument.getPipValue(), instrument);
                                order.setStopLossPrice(sl);
                                //order.setTakeProfitPrice(tp);
                            }
                        }
                    } else {
                        if(order.getProfitLossInPips() > trailingStop) {
                            closePartially(order);
                            if(order.getStopLossPrice() > (tick.getBid() + trailingStop*instrument.getPipValue())) {
                                //double tp = roundToPippette(tick.getBid() - (takeProfitPips+trailingStop)*instrument.getPipValue(), instrument);
                                double sl = roundToPippette(tick.getBid() + trailingStop*instrument.getPipValue(), instrument);
                                order.setStopLossPrice(sl);
                                //order.setTakeProfitPrice(tp);
                            }
                        }
                    }
                }
            }
        }
    }

    private void manageZeroLines(Period period, IBar bar) throws JFException {
        IBar lastBar = getPreviousBar(period, bar);
        ZeroLine.removeOldLines(zeroLines, bar, period);
        double lastBarSize = getBarSize(lastBar);
        double currentBarSize = getBarSize(bar);
        if(currentBarSize > 2 * lastBarSize) {
            if(getBarDirection(bar) == Direction.UP && lastBar.getHigh() < bar.getClose()) {
                ZeroLine zl = new ZeroLine(lastBar.getHigh(), IEngine.OrderCommand.BUY, period, lastBar.getTime(), chart);
                zeroLines.add(zl);
                zl.render();
            }
            if(getBarDirection(bar) == Direction.DOWN && lastBar.getLow() > bar.getClose()) {
                ZeroLine zl = new ZeroLine(lastBar.getLow(), IEngine.OrderCommand.SELL, period, lastBar.getTime(), chart);
                zeroLines.add(zl);
                zl.render();
            }
        }
    }

    private IBar getPreviousBar(Period period, IBar bar) throws JFException {
        IBar lastBar = history.getBars(instrument, period, OfferSide.BID, bar.getTime() - period.getInterval(), bar.getTime()).get(0);
        return lastBar;
    }

    private Direction getBarDirection(IBar bar) {
        if(bar.getClose() > bar.getOpen()) {
            return Direction.UP;
        }
        else if(bar.getClose() < bar.getOpen()) {
            return Direction.DOWN;
        }
        return null;
    }

    private double getBarSize(IBar bar) {
        return Math.abs(bar.getClose() - bar.getOpen());
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

    private void closePartially(IOrder order) throws JFException {
        if(order.getState() == IOrder.State.FILLED) {
            if(order.getAmount() == lots && order.getProfitLossInPips() >= trailingStop) {
                closeOrder(order, 0.5);
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
        if(message.getType() == IMessage.Type.ORDER_CHANGED_OK || message.getType() == IMessage.Type.ORDER_CHANGED_REJECTED) {
            System.out.println(message);
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
