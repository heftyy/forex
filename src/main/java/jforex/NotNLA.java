package main.java.jforex;

import com.dukascopy.api.*;
import com.dukascopy.api.drawings.IChartObjectFactory;
import com.dukascopy.api.drawings.IPriceMarkerChartObject;
import com.dukascopy.api.drawings.IShortLineChartObject;
import org.joda.time.DateTime;

import java.awt.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Library("/home/heftyy/JForex/Strategies/libs/joda-time-2.3.jar")
public class NotNLA implements IStrategy {

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
//            if(this.period == Period.FOUR_HOURS) shortLine.setColor(DARK_GREEN);
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

        protected static void updateZLTime(List<ZeroLine> zeroLines) {
            for (Iterator<ZeroLine> it = zeroLines.iterator(); it.hasNext();) {
                ZeroLine zl = it.next();
                zl.endLine(System.currentTimeMillis());
            }
        }

        private void render() {
            shortLine = factory.createShortLine("zl_"+uuid.toString(),
                    time.getMillis(), price,
                    System.currentTimeMillis(), price);
//            shortLine.setText("ZL "+this.command+" "+this.period);
            if(this.command == IEngine.OrderCommand.BUY) shortLine.setColor(DARK_GREEN);
            if(this.command == IEngine.OrderCommand.SELL) shortLine.setColor(Color.RED);
//            if(this.period == Period.FOUR_HOURS) shortLine.setColor(DARK_GREEN);
            chart.add(shortLine);
        }

        @Override
        public String toString() {
            return this.price+"   \t"+this.period+"   \t"+this.command+"   \t"+this.time;
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
    public Period period = Period.FIFTEEN_MINS;
    @Configurable("Period ZL")
    public Period periodZL = Period.FOUR_HOURS;
    @Configurable("TP before ZL")
    public int takeProfitBeforeZLPips = 12;
    @Configurable("TP max [pips]")
    public int takeProfitMaxPips = 30;
    @Configurable("TP min [pips]")
    public int takeProfitMinPips = 0;
    @Configurable("SL [pips]")
    public int stopLossPips = 30;
    @Configurable("Min bar size[pips]")
    public int minBarSize = 1;
//    @Configurable("Min difference for sequence bars [pips]")
    public int minDifferenceForSequence = 5;
    @Configurable("Attempt close after profit[pips]")
    public int attemptCloseAfterProfit = 30;
    @Configurable("Trailing step[pips]")
    public int trailingStep = 1;
    @Configurable("Trailing stop[pips]")
    public int trailingStop = 10;
    @Configurable("Draw SL levels on chart")
    public boolean drawSl = true;
    @Configurable("")
    public Set<Period> periods = new HashSet<Period>(
        Arrays.asList(new Period[]{Period.DAILY})
    );

    private static Color DARK_GREEN = Color.getHSBColor(102f / 360, 1f, 0.4f);

    private Map<IOrder, Double> slPrices = new ConcurrentHashMap<IOrder, Double>();

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

    public void onStart(IContext context) throws JFException {
        this.context = context;
        this.engine = context.getEngine();
        this.indicators = context.getIndicators();
        this.history = context.getHistory();
        this.console = context.getConsole();
        this.chart = context.getChart(Instrument.EURUSD);
        this.zeroLines = new ArrayList<>();

        long prevBarTime = history.getPreviousBarStart(this.periodZL, history.getLastTick(Instrument.EURUSD).getTime());
        List<IBar> bars = history.getBars(instrument, this.periodZL, OfferSide.BID, history.getTimeForNBarsBack(periodZL, prevBarTime, 300), prevBarTime);

        for(IBar bar : bars) {
            manageZeroLines(this.periodZL, bar);
        }

        subscriptionInstrumentCheck(instrument);

        this.console.getOut().println("Started");
    }

    public void onStop() throws JFException {
        closeAll();
        console.getOut().println("Stopped");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        trailingStop(tick);
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {

        if(period.equals(periodZL)) {
            manageZeroLines(periodZL, bidBar);
        }

        IBar lastBar = context.getHistory().getBar(instrument, period, OfferSide.ASK, 2);

        if (period == this.period && positionsTotal(instrument) == 0)  {
            if(period == Period.FIFTEEN_MINS) {
                DateTime barTime = new DateTime(askBar.getTime());

                if(barTime.getMinuteOfHour() != 15) {
                    return;
                }
            }

            ZeroLine buyZL = findClosestZL(bidBar.getClose(), periodZL, IEngine.OrderCommand.BUY);
            ZeroLine sellZL = findClosestZL(bidBar.getClose(), periodZL, IEngine.OrderCommand.SELL);

            //LONG
            if(checkBars(lastBar, askBar) == IEngine.OrderCommand.BUY) {
                boolean trendMatch = true;

                for(Period p : periods) {
                    if(getBarDirection(context.getHistory().getBar(instrument, p, OfferSide.ASK, 0)) == Direction.DOWN) {
                        trendMatch = false;
                    }
                }

                if(!trendMatch && takeProfitMinPips == 0) return;


                double takeProfit, stopLoss;
                double price = history.getLastTick(instrument).getAsk();
                if(trendMatch) {
                    if(buyZL != null)
                        takeProfit = buyZL.price - instrument.getPipValue() * takeProfitBeforeZLPips;
                    else
                        takeProfit = askBar.getClose() + instrument.getPipValue() * takeProfitMaxPips;

                    stopLoss = price - instrument.getPipValue() * stopLossPips;
                }
                else {
                    if(buyZL != null)
                        takeProfit = buyZL.price - instrument.getPipValue() * takeProfitBeforeZLPips;
                    else
                        takeProfit = askBar.getClose() + instrument.getPipValue() * takeProfitMinPips;

                    stopLoss = price - instrument.getPipValue() * stopLossPips;
                }
                openOrder(instrument, IEngine.OrderCommand.BUY, lots, stopLossPips > 0 ? stopLoss : 0, takeProfit);
            }

            //SHORT
            if(checkBars(lastBar, askBar) == IEngine.OrderCommand.SELL) {

                boolean trendMatch = true;

                for(Period p : periods) {
                    if(getBarDirection(context.getHistory().getBar(instrument, p, OfferSide.BID, 0)) == Direction.UP) {
                        trendMatch = false;
                    }
                }

                if(!trendMatch && takeProfitMinPips == 0) return;


                double takeProfit, stopLoss;
                double price = history.getLastTick(instrument).getAsk();
                if(trendMatch) {
                    if(sellZL != null)
                        takeProfit = sellZL.price + instrument.getPipValue() * takeProfitBeforeZLPips;
                    else
                        takeProfit = bidBar.getClose() - instrument.getPipValue() * takeProfitMaxPips;

                    stopLoss = price + instrument.getPipValue() * stopLossPips;
                }
                else {
                    if(sellZL != null)
                        takeProfit = sellZL.price + instrument.getPipValue() * takeProfitBeforeZLPips;
                    else
                        takeProfit = bidBar.getClose() - instrument.getPipValue() * takeProfitMinPips;

                    stopLoss = price + instrument.getPipValue() * stopLossPips;
                }
                openOrder(instrument, IEngine.OrderCommand.SELL, lots, stopLossPips > 0 ? stopLoss : 0, takeProfit);
            }
        }

        else if (period == this.period && positionsTotal(instrument) > 0)  {
            checkForClose(lastBar, askBar);
        }
    }

    private void trailingStop(ITick tick) throws JFException {
        //add trailing stops
        for (IOrder order : engine.getOrders(instrument)) {
            if (!(order.getState() == IOrder.State.FILLED && Double.compare(order.getRequestedAmount(), order.getAmount()) == 0) // instrument's position
                    || (slPrices.get(order) == null)) {
                continue;
            }
            double prevSl = slPrices.get(order) == null ? 0 : slPrices.get(order);
            double marketPrice = order.isLong() ? tick.getBid() : tick.getAsk();
            int sign = order.isLong() ? +1 : -1;
            double slInPips = Math.abs(marketPrice - prevSl) / instrument.getPipValue();
            if (slInPips > trailingStop + trailingStep) {
                double newSl = marketPrice - (sign * trailingStop * instrument.getPipValue());
                slPrices.put(order, newSl);
                print("%s of %s moved SL %.5f -> %.5f", order.getLabel(), order.getInstrument(), prevSl, newSl);
                if(drawSl){
                    for(IChart chart : context.getCharts(instrument)){
                        IPriceMarkerChartObject line = chart.getChartObjectFactory().createPriceMarker(order.getId(), newSl);
                        line.setText("SL for " + order.getId());
                        line.setColor(Color.GRAY);
                        line.setLineStyle(LineStyle.FINE_DASHED);
                        chart.add(line);
                    }
                }
            }
        }

        //check if SL levels are reached
        Iterator<Map.Entry<IOrder, Double>> entries = slPrices.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<IOrder, Double> entry = entries.next();
            IOrder order = entry.getKey();
            double slPrice = entry.getValue();
            if(order.getInstrument() != instrument){
                continue;
            }

            double marketPrice = order.isLong() ? tick.getBid() : tick.getAsk();
            if ((order.isLong() && slPrice >= marketPrice) || (!order.isLong() && slPrice <= marketPrice)) {
                print("%s of %s breached SL level of %.5f (last %s=%.5f), creating an oposite direction Market order to close the position",
                        order.getLabel(),
                        order.getInstrument(),
                        slPrice,
                        order.isLong() ? "BID" : "ASK",
                        marketPrice
                );
                engine.submitOrder("OppDirOrder_"+System.currentTimeMillis(), instrument, order.isLong() ? IEngine.OrderCommand.SELL : IEngine.OrderCommand.BUY, order.getAmount());
                entries.remove();
                if(drawSl){
                    for(IChart chart : context.getCharts(instrument)){
                        chart.remove(order.getId());
                    }
                }
            }
        }
    }

    private void checkForClose(IBar lastBar, IBar bar) throws JFException {
        for(IOrder order : engine.getOrders()) {
            if (order.getState() == IOrder.State.FILLED) {
                if(order.getProfitLossInPips() < attemptCloseAfterProfit) continue;

                //LONG
                if(order.isLong()) {
                    if(checkBars(lastBar, bar) == IEngine.OrderCommand.SELL) order.close();
                }
                //SELL
                else {
                    if(checkBars(lastBar, bar) == IEngine.OrderCommand.BUY) order.close();
                }
            }
        }
    }

    private IEngine.OrderCommand checkBars(IBar lastBar, IBar bar) throws JFException {
        if(getBarSizePips(lastBar) < minBarSize || getBarSizePips(bar) < minBarSize) return null;

        IEngine.OrderCommand result;
        result = checkBarsInverse(lastBar, bar);
        if(result != null) return result;

        /*
        result = checkBarsSequence(lastBar, bar);
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

    private void manageZeroLines(Period period, IBar bar) throws JFException {
        IBar lastBar = getPreviousBar(period, bar);
        ZeroLine.removeOldLines(zeroLines, bar, period);
        ZeroLine.updateZLTime(zeroLines);
        double lastBarSize = getBarSizePips(lastBar);
        double currentBarSize = getBarSizePips(bar);

        Direction barDirection = getBarDirection(bar);
        Direction lastBarDirection = getBarDirection(lastBar);

        if(barDirection == null || lastBarDirection == null) return;

        //create ZL when the current bar is twice the size of the last one and they have different directions
        if(currentBarSize > 2 * lastBarSize && barDirection != lastBarDirection) {
            if(getBarDirection(bar) == Direction.UP && lastBar.getHigh() < bar.getClose()) {
                ZeroLine zl = new ZeroLine(lastBar.getHigh(), IEngine.OrderCommand.BUY, period, lastBar.getTime(), chart);
                print(zl.toString());
                print(String.format("bar [%f][%f] (%s)", bar.getOpen(), bar.getClose(), new DateTime(bar.getTime())));
                print("");
                zeroLines.add(zl);
                zl.render();
            }
            if(getBarDirection(bar) == Direction.DOWN && lastBar.getLow() > bar.getClose()) {
                ZeroLine zl = new ZeroLine(lastBar.getLow(), IEngine.OrderCommand.SELL, period, lastBar.getTime(), chart);
                print(zl.toString());
                print(String.format("bar [%f][%f] (%s)", bar.getOpen(), bar.getClose(), new DateTime(bar.getTime())));
                print("");
                zeroLines.add(zl);
                zl.render();
            }
        }
    }

    private ZeroLine findClosestZL(double price, Period period, IEngine.OrderCommand cmd) {
        ZeroLine result = null;
        for(ZeroLine zl : zeroLines) {
            if(period != zl.period) continue;
            if(cmd == IEngine.OrderCommand.BUY) {
                if(zl.price > price && result == null) result = zl;
                if(result != null && zl.price > price && zl.price < result.price) result = zl;
            }
            else if(cmd == IEngine.OrderCommand.SELL) {
                if(zl.price < price && result == null) result = zl;
                if(result != null && zl.price < price && zl.price > result.price) result = zl;
            }
        }
        return result;
    }


    private IBar getPreviousBar(Period period, IBar bar) throws JFException {
        IBar lastBar = history.getBars(instrument, period, OfferSide.BID, bar.getTime() - period.getInterval(), bar.getTime()).get(0);
        return lastBar;
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

    private int getBarSizePips(IBar bar) {
        Double difference = Math.abs(bar.getOpen() - bar.getClose());
        Long differencePips = Math.round(difference / instrument.getPipValue());
        return differencePips.intValue();
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
            String out = String.format("[%s] %s ORDER PLACED FOR %f EUR", new DateTime(history.getLastTick(instrument).getTime()).toString(), command, lot*1000000/100);
            System.out.println(out);
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

    protected void print(String msg) {
        console.getInfo().println(msg);
    }

    protected void print(String msg, Object ...args) {
        console.getInfo().println(String.format(msg, args));
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
