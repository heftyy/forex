package main.java.jforex;

import com.dukascopy.api.*;
import com.dukascopy.api.drawings.IChartObjectFactory;
import com.dukascopy.api.drawings.IShortLineChartObject;
import org.joda.time.DateTime;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Library("/home/heftyy/JForex/Strategies/libs/joda-time-2.3.jar")
public class TooSimple implements IStrategy {

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
//            shortLine.setColor(Color.LIGHT_GRAY);
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
            shortLine.setText("ZL "+this.period);
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
    @Configurable("Minor period")
    public Period minorPeriod = Period.FIFTEEN_MINS;
    @Configurable("Major period")
    public Period majorPeriod = Period.ONE_HOUR;
    @Configurable("Period ZL")
    public Set<Period> periodsZL = new HashSet<>(
            Arrays.asList(new Period[]{Period.FOUR_HOURS, Period.DAILY})
    );
    @Configurable("Max bar size[pips]")
    public int maxBarSize = 100;
    @Configurable("Min TP distance to ZL[pips]")
    public int minTPdistanceToZL = 30;
    @Configurable("TP before ZL")
    public int takeProfitBeforeZLPips = 12;
    @Configurable("TP no zl [pips]")
    public int takeProfitPips = 0;
    @Configurable("SL [pips]")
    public int stopLossPips = 30;
    @Configurable("Trailing step[pips]")
    public int trailingStep = 40;
    @Configurable("Trailing stop trigger[pips]")
    public int trailingStopTrigger = 30;
    @Configurable("Check for close")
    public boolean checkForClose = true;
    @Configurable("Market open hour")
    public int marketOpenHour = 7;
    @Configurable("Market close hour")
    public int marketCloseHour = 16;
    @Configurable("")
    public Set<Period> periods = new HashSet<>(
        Arrays.asList(new Period[]{})
    );

    private static Color DARK_GREEN = Color.getHSBColor(102f / 360, 1f, 0.4f);
    private static int hourInMillis = 1000 * 60 * 60;

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

        for(Period periodZL : periodsZL) {
            long prevBarTime = history.getPreviousBarStart(periodZL, history.getLastTick(Instrument.EURUSD).getTime());
            List<IBar> bars = history.getBars(instrument, periodZL, OfferSide.BID, history.getTimeForNBarsBack(periodZL, prevBarTime, 300), prevBarTime);

            for(IBar bar : bars) {
                manageZeroLines(periodZL, bar);
            }
        }

        subscriptionInstrumentCheck(instrument);

        this.console.getOut().println("Started");
    }

    public void onStop() throws JFException {
        closeAll();
        console.getOut().println("Stopped");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if (trailingStopTrigger > 0) {
            for(IOrder order : engine.getOrders()) {
                updateTrailingStopLoss(order, tick, trailingStopTrigger, trailingStep);
            }
        }
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {

        for(Period periodZL : periodsZL) {
            if(period.equals(periodZL)) {
                manageZeroLines(periodZL, bidBar);
            }
        }

        //close orders
        if(period == minorPeriod && positionsTotal(instrument) > 0 && checkForClose) {
            checkForClose(askBar);
        }

        //open orders
        if (period == majorPeriod && positionsTotal(instrument) == 0)  {
            DateTime barTime = new DateTime(askBar.getTime());
            //only open orders after marketOpenHour and before marketCloseHour
            if(barTime.getHourOfDay() < marketOpenHour || barTime.getHourOfDay() > marketCloseHour) {
                return;
            }

            //check if the current bar isn't too big so we don't open order after a big price change
            if(askBar.getHigh() - askBar.getLow() > pip(maxBarSize, instrument)) {
                return;
            }

            IBar lastMajorBar = context.getHistory().getBar(instrument, majorPeriod, OfferSide.ASK, 2);

            //get all fifteen minutes bars from the last hour bar
            List<IBar> shortBars = context.getHistory().getBars(instrument, minorPeriod, OfferSide.ASK, lastMajorBar.getTime() + hourInMillis, askBar.getTime() + hourInMillis);
            //remove the last one because getBars periods are inclusive and it returns one bar too many
            shortBars.remove(4);

            IEngine.OrderCommand transactionType = getTransactionType(lastMajorBar, shortBars);

            //LONG
            if(transactionType == IEngine.OrderCommand.BUY) {
                ZeroLine buyZL = findClosestZL(bidBar.getClose(), IEngine.OrderCommand.BUY);

                boolean trendMatch = true;

                for(Period p : periods) {
                    if(getBarDirection(context.getHistory().getBar(instrument, p, OfferSide.ASK, 0)) == Direction.DOWN) {
                        trendMatch = false;
                    }
                }

                if(!trendMatch) return;

                double takeProfit = 0, stopLoss = 0;
                double price = history.getLastTick(instrument).getAsk();

                if(buyZL != null)
                    takeProfit = buyZL.price - instrument.getPipValue() * takeProfitBeforeZLPips;
                else {
                    if(takeProfitPips > 0)
                        takeProfit = askBar.getClose() + instrument.getPipValue() * takeProfitPips;
                }

                stopLoss = price - instrument.getPipValue() * stopLossPips;
                openOrder(instrument, IEngine.OrderCommand.BUY, lots, stopLossPips > 0 ? stopLoss : 0, takeProfit);
            }

            //SHORT
            if(transactionType == IEngine.OrderCommand.SELL) {
                ZeroLine sellZL = findClosestZL(bidBar.getClose(), IEngine.OrderCommand.SELL);

                boolean trendMatch = true;

                for(Period p : periods) {
                    if(getBarDirection(context.getHistory().getBar(instrument, p, OfferSide.BID, 0)) == Direction.UP) {
                        trendMatch = false;
                    }
                }

                if(!trendMatch) return;

                double takeProfit = 0, stopLoss;
                double price = history.getLastTick(instrument).getAsk();

                if(sellZL != null)
                    takeProfit = sellZL.price + instrument.getPipValue() * takeProfitBeforeZLPips;
                else {
                    if(takeProfitPips > 0)
                        takeProfit = askBar.getClose() - instrument.getPipValue() * takeProfitPips;
                }

                stopLoss = price + instrument.getPipValue() * stopLossPips;
                openOrder(instrument, IEngine.OrderCommand.SELL, lots, stopLossPips > 0 ? stopLoss : 0, takeProfit);
            }
        }
    }

    public void updateTrailingStopLoss(IOrder order, ITick tick, double trailingStopTrigger, double trailingStopStep) throws JFException {

        if (order != null && order.getState() == IOrder.State.FILLED) {

            Instrument instr = order.getInstrument();

            double newStop;
            double openPrice = order.getOpenPrice();
            double currentStopLoss = order.getStopLossPrice();

            // (START) trailing stop loss is activated when price is higher than oper price + trailingTrigger pips
            // (TRAILING STOP) if price moves further up (for BUY order), stop loss is updated to stopLossPips

            if (order.isLong()) { // long side order
                if ((currentStopLoss == 0.0 || tick.getBid() > currentStopLoss + pip(trailingStopStep, instr))
                        && tick.getBid() > openPrice + pip(trailingStopTrigger, instr)) {
                    // trailing stop loss
                    newStop = tick.getBid() - pip(trailingStopStep, instr);
                    newStop = round(newStop, instr);

                    if (currentStopLoss != newStop) {
                        order.setStopLossPrice(newStop);
                        return;
                    }
                }

            } else { // short side order
                if ((currentStopLoss == 0.0 || tick.getAsk() < currentStopLoss - pip(trailingStopStep, instr))
                        && tick.getAsk() < openPrice - pip(trailingStopTrigger, instr)) {

                    // trailing stop loss
                    newStop = tick.getAsk() + pip(trailingStopStep, instr);
                    newStop = round(newStop, instr);

                    if (currentStopLoss != newStop) {
                        order.setStopLossPrice(newStop);
                        return;
                    }
                }
            }
        }
    }

    private IEngine.OrderCommand getTransactionType(IBar lastHourBar, List<IBar> fifteenMinsBars) {
        double high = lastHourBar.getHigh();
        double low = lastHourBar.getLow();

        IEngine.OrderCommand transaction = null;

        for (IBar bar : fifteenMinsBars) {
            if (bar.getClose() > high) {
                transaction = IEngine.OrderCommand.BUY;
            }
            if (bar.getClose() < low) {
                transaction = IEngine.OrderCommand.SELL;
            }
        }
        return transaction;
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

    private ZeroLine findClosestZL(double price, IEngine.OrderCommand cmd) {
        ZeroLine result = null;
        for(ZeroLine zl : zeroLines) {
            if(cmd == IEngine.OrderCommand.BUY) {
                double minPrice = price + pip(minTPdistanceToZL + takeProfitBeforeZLPips, instrument);

                if(zl.price > minPrice && result == null) result = zl;
                if(result != null && zl.price > price && zl.price < result.price) result = zl;
            }
            else if(cmd == IEngine.OrderCommand.SELL) {
                double maxPrice = price - pip(minTPdistanceToZL + takeProfitBeforeZLPips, instrument);

                if(zl.price < maxPrice && result == null) result = zl;
                if(result != null && zl.price < maxPrice && zl.price > result.price) result = zl;
            }
        }
        return result;
    }

    private void checkForClose(IBar minorBar) throws JFException {
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getState() == IOrder.State.FILLED) {
                long orderBarTime = history.getBarStart(majorPeriod, order.getFillTime());
                //if the major bar in which the order was placed is not finished yet skip this check
                if(orderBarTime + hourInMillis > history.getLastTick(instrument).getTime())
                    return;

                IBar orderBar = history.getBars(instrument, majorPeriod, OfferSide.ASK, orderBarTime, orderBarTime+hourInMillis).get(0);
                if(order.isLong()) {
                    if(minorBar.getClose() < orderBar.getLow()) {
                        order.close();
                    }
                }
                else {
                    if(minorBar.getClose() > orderBar.getHigh()) {
                        order.close();
                    }
                }
            }
        }
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
        takeProfit = round(takeProfit, instrument);
        for (IOrder order : engine.getOrders(instrument)) {
            if (order.getState() == IOrder.State.FILLED) {
                if (takeProfit != 0.0d && takeProfit != order.getTakeProfitPrice()) {
                    order.setTakeProfitPrice(takeProfit);
                }
            }
        }
    }

    protected void setStopLoss(Instrument instrument, double stopLoss) throws JFException {
        stopLoss = round(stopLoss, instrument);
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
            profit = round(profit/ lot, instrument);
        }
        return profit;
    }

    private double round(double price, Instrument instr) {
        BigDecimal bd = new BigDecimal(price);
        bd = bd.setScale(instr.getPipScale() + 1, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private double pip(double pips, Instrument instr) {
        return pips * instr.getPipValue();
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
