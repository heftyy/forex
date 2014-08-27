package main.java.jforex;

import com.dukascopy.api.*;
import org.joda.time.DateTime;
import org.joda.time.LocalTime;

import java.math.BigDecimal;
import java.util.*;
import java.util.List;

@Library("/home/heftyy/JForex/Strategies/libs/joda-time-2.3.jar")
public class CrazyHedge implements IStrategy {

    public enum Direction { UP, DOWN }

    protected class Transaction {
        public int level = 1;
        public int winningStreak = 0;
        public IOrder order;
        public Double price;

        //if parent is NOT null transaction is a hedge, will NOT be hedged again
        public Transaction parent;
        public Transaction hedge;
        public Transaction nextProfit;

        public Transaction(IEngine.OrderCommand command, double price, int level, Transaction parent) {
//            if(level <= 0) level = 1;
            if(level == 0) return;
            /*
            if(level >= 4) {
                console.getOut().println("Max level reached, not opening any more transactions");
                return;
            }
            */

            Double takeProfit = null;
            if(command == IEngine.OrderCommand.BUY || command == IEngine.OrderCommand.BUYSTOP) {
                takeProfit = price + takeProfitPips * instrument.getPipValue();
            }
            else if(command == IEngine.OrderCommand.SELL || command == IEngine.OrderCommand.SELLSTOP) {
                takeProfit = price - takeProfitPips * instrument.getPipValue();
            }
            if(takeProfit == null) {
                return;
            }

            double stopLoss = 0.0d;

            if(stopLossPips > 0) {
                if(command == IEngine.OrderCommand.BUY || command == IEngine.OrderCommand.BUYSTOP) {
                    stopLoss = price - stopLossPips * instrument.getPipValue();
                }
                else if(command == IEngine.OrderCommand.SELL || command == IEngine.OrderCommand.SELLSTOP) {
                    stopLoss = price + stopLossPips * instrument.getPipValue();
                }
            }

            //console.getOut().println("STOP LOSS FOR ORDER IS = " + stopLoss);

            this.price = price;
            this.parent = parent;
            this.level = level;
            this.winningStreak = level - 1;

            try {
                order = openOrder(instrument, command, price, lots * level, stopLoss, takeProfit);
            } catch (JFException e) {
                e.printStackTrace();
            }
        }

        public Transaction(IEngine.OrderCommand command, double price) {
            this(command, price, 1);
        }

        public Transaction(IEngine.OrderCommand command, double price, int level) {
            this(command, price, level, null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Transaction that = (Transaction) o;

            if (level != that.level) return false;
            if (winningStreak != that.winningStreak) return false;
            if (!order.equals(that.order)) return false;
            return price.equals(that.price);

        }

        @Override
        public int hashCode() {
            int result = level;
            result = 31 * result + winningStreak;
            result = 31 * result + order.hashCode();
            result = 31 * result + price.hashCode();
            return result;
        }

        public List<Transaction> addWaitingOrders() {
            if(order.getOrderCommand() == IEngine.OrderCommand.BUY) {
                this.nextProfit = new Transaction(IEngine.OrderCommand.BUYSTOP, order.getTakeProfitPrice(), level + 1, this);
                this.hedge = new Transaction(IEngine.OrderCommand.SELLSTOP, order.getOpenPrice() - (openHedgeAfterLossPips * instrument.getPipValue()), level - 1, this);
            }
            else if(order.getOrderCommand() == IEngine.OrderCommand.SELL) {
                this.nextProfit = new Transaction(IEngine.OrderCommand.SELLSTOP, order.getTakeProfitPrice(), level + 1, this);
                this.hedge = new Transaction(IEngine.OrderCommand.BUYSTOP, order.getOpenPrice() + (openHedgeAfterLossPips * instrument.getPipValue()), level - 1, this);
            }

            if(hedge != null && !hedge.isValid()) hedge = null;

            List<Transaction> transactions = new ArrayList<Transaction>();
            if(this.hedge != null) transactions.add(this.hedge);
            if(this.nextProfit != null) transactions.add(this.nextProfit);
            return transactions;
        }

        /*
        public boolean watchLoss(double price) throws JFException {
            if(!isValid()) return false;
            //if(parent == null && hedge == null && order != null && order.getState() == IOrder.State.FILLED && order.getProfitLossInPips() < -openHedgeAfterLossPips) {
            if(hedge == null && order != null && order.getState() == IOrder.State.FILLED && order.getProfitLossInPips() < -openHedgeAfterLossPips) {
                IEngine.OrderCommand hedgeCommand = null;
                if(this.order.getOrderCommand() == IEngine.OrderCommand.BUY) {
                    hedgeCommand = IEngine.OrderCommand.SELL;
                }
                if(this.order.getOrderCommand() == IEngine.OrderCommand.SELL) {
                    hedgeCommand = IEngine.OrderCommand.BUY;
                }
                hedge = new Transaction(hedgeCommand, price, level-1, this);

                return true;
            }
            return false;
        }
        */

        /*
        public boolean watchProfit(double price) {
            if(!isValid()) return false;
            if(order.getState() == IOrder.State.CLOSED) {
                //console.getOut().println("profit loss in pips = "+order.getProfitLossInPips());
            }

            if(order.getState() == IOrder.State.CLOSED && order.getProfitLossInPips() > 0) {
                //main transaction, no parent
                if(parent == null) {
                    nextProfit = new Transaction(order.getOrderCommand(), price, level+1);
                }
                //hedge transaction, hes a parent
                else {
                    if(parent.isValid() && continueOpeningHedges) {
                        nextProfit = new Transaction(order.getOrderCommand(), price, level+1, this);
                    }
                }
                order = null;
                return true;
            }
            return false;
        }
        */

        public boolean isValid() {
            return order != null;
        }

        /**
         * Doesn't stop stop loss and take profit, uses the current price.
         * @return
         * @throws JFException
         */
        protected IOrder openOrder(Instrument instrument, IEngine.OrderCommand command, double lot) throws JFException {
            return openOrder(instrument, command, lot, 0, 0);
        }

        /**
         * Uses the current price.
         * @throws JFException
         */
        IOrder openOrder(Instrument instrument, IEngine.OrderCommand command, double lot, double stopLoss, double takeProfit) throws JFException {
            double lastAskPrice = history.getLastTick(instrument).getAsk();
            return openOrder(instrument, command, lastAskPrice, lot, stopLoss, takeProfit);
        }

        IOrder openOrder(Instrument instrument, IEngine.OrderCommand command, double price, double lot, double stopLoss, double takeProfit) throws JFException {
            IOrder order = null;
            if ( command == IEngine.OrderCommand.BUY || command == IEngine.OrderCommand.BUYSTOP ) {
                order = engine.submitOrder(getLabel(instrument), instrument, command, lot, price, slippage, stopLoss, takeProfit);
            } else if ( command == IEngine.OrderCommand.SELL || command == IEngine.OrderCommand.SELLSTOP ) {
                order = engine.submitOrder(getLabel(instrument), instrument, command, lot, price, slippage, stopLoss, takeProfit);
            }
            if ( order != null ) {
                console.getOut().println("ORDER PLACED FOR = " + lot * 1000000 / 100 + " EUR at " + new DateTime(history.getBar(instrument, periodOpen, OfferSide.ASK, 0).getTime()).toString());
            }
            return order;
        }

        protected void trailingStop(ITick tick) throws JFException {

            if(order.getState() == IOrder.State.FILLED) {
                if(trailingStop > 0) {
                    if(order.isLong()) {
                        if(order.getProfitLossInPips() > trailingStop) {
                            closePartially();
                            if(order.getStopLossPrice() < (tick.getAsk() - trailingStop*instrument.getPipValue())) {
                                //double tp = roundToPippette(tick.getAsk() + (takeProfitPips +trailingStop)*instrument.getPipValue(), instrument);
                                double sl = roundToPippette(tick.getAsk() - trailingStop*instrument.getPipValue(), instrument);
                                order.setStopLossPrice(sl);
                                //order.setTakeProfitPrice(tp);
                            }
                        }
                    } else {
                        if(order.getProfitLossInPips() > trailingStop) {
                            closePartially();
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

        private void closePartially() throws JFException {
            if(order.getState() == IOrder.State.FILLED) {
                if(order.getAmount() == lots && order.getProfitLossInPips() >= trailingStop) {
                    closeOrder(0.5);
                }
            }
        }

        void closeOrder(double part) throws JFException {
            if(order != null && (order.getState() == IOrder.State.FILLED || order.getState() == IOrder.State.OPENED) ) {
                if(part == 1) {
                    order.close();
                } else {
                    double orderLot = order.getAmount();
                    order.close(orderLot * part);
                }
            }
        }

    }

    private IEngine engine = null;
    private IIndicators indicators = null;
    private IHistory history = null;
    private int tagCounter = 0;
    private IConsole console;
    private IContext context = null;

    @Configurable("Lot")
    public double lots = 0.01;
    @Configurable("Slippage")
    public int slippage = 5;
    @Configurable("Period open")
    public Period periodOpen = Period.FIVE_MINS;
    @Configurable("TP[pips]")
    public int takeProfitPips = 20;
    @Configurable("SL[pips]")
    public int stopLossPips = 0;
    /*
    @Configurable("Trailing stop[pips]")
    */
    public int trailingStop  = 50;
    @Configurable("Open hedge after loss[pips]")
    public int openHedgeAfterLossPips = 6;
    @Configurable("ADX cross valid for[minutes]")
    public int validCrossForMinutes = 5;
    @Configurable("ADX period")
    public int adxPeriod = 14;
    @Configurable("ADX level")
    public int adxEntryLevel = 23;
    @Configurable("Continue opening hedges after profit")
    public boolean continueOpeningHedges = true;
    @Configurable("Close all after stop loss hit")
    public boolean closeAllAfterLoss = false;
    @Configurable("Target profit[percentage]")
    public Integer targetProfitPerc = 5;

    public boolean waitForAdx = true;
    public Double startMoney;

    private Instrument instrument = Instrument.EURUSD;

    private long lastCrossTime;
    private List<Transaction> transactionList;

    private LocalTime openTimeStart;
    private LocalTime openTimeEnd;

    public void onStart(IContext context) throws JFException {
        this.context = context;
        this.engine = context.getEngine();
        this.indicators = context.getIndicators();
        this.history = context.getHistory();
        this.console = context.getConsole();
        IChart chart = context.getChart(Instrument.EURUSD);

        subscriptionInstrumentCheck(instrument);

        this.transactionList = new ArrayList<Transaction>();

        this.console.getInfo().println("Started");

        IAccount account = context.getAccount();
        startMoney = account.getEquity();
    }

    public void onStop() throws JFException {
        closeAll();
        console.getInfo().println("Stopped");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        if(waitForAdx) return;

        /*
        int positions = positionsTotal(instrument);

        if(positions == 0) {

        }
        else if(positions > 0) {
            for(int i = 0; i < transactionList.size(); i++) {
                Transaction transaction = transactionList.get(i);
                if(transaction == null) continue;

                if(transaction.isValid()) {

                    if(transaction.watchLoss(tick.getBid())) {
                        transactionList.add(transaction.hedge);
                    }
                }
                else {
                    transaction.order = null;
                }
            }

            for(Iterator<Transaction> iterator = transactionList.iterator(); iterator.hasNext(); ) {
                Transaction transaction = iterator.next();
                if(transaction != null && !transaction.isValid()) {
                    iterator.remove();
                }
            }
        }
        */
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {

        if(period == this.periodOpen) {

            double adxLast = indicators.adx(instrument, periodOpen, OfferSide.BID, adxPeriod, 2);
            double adxCurrent = indicators.adx(instrument, periodOpen, OfferSide.BID, adxPeriod, 1);
            double adxDiPlusLast = indicators.plusDi(instrument, periodOpen, OfferSide.BID, adxPeriod, 2);
            double adxDiPlusCurrent = indicators.plusDi(instrument, periodOpen, OfferSide.BID, adxPeriod, 1);
            double adxDiMinusLast = indicators.minusDi(instrument, periodOpen, OfferSide.BID, adxPeriod, 2);
            double adxDiMinusCurrent = indicators.minusDi(instrument, periodOpen, OfferSide.BID, adxPeriod, 1);

            if(hasAdxCrossed(adxDiPlusLast, adxDiPlusCurrent, adxDiMinusLast, adxDiMinusCurrent)) {
                lastCrossTime = askBar.getTime();
            }

            if(positionsTotal(instrument) == 0) {
                IBar hourBar = history.getBar(instrument, Period.ONE_HOUR, OfferSide.BID, 0);
                IBar fourHourBar = history.getBar(instrument, Period.FOUR_HOURS, OfferSide.BID, 0);
                IBar dailyBar = history.getBar(instrument, Period.DAILY, OfferSide.BID, 0);

                //LONG
                if(getBarDirection(fourHourBar) == Direction.UP && getBarDirection(dailyBar) == Direction.UP) {

                    if(((checkForValidCross(lastCrossTime, bidBar.getTime()) && ((adxDiPlusCurrent > adxEntryLevel && adxDiMinusCurrent < adxEntryLevel) ||
                            (adxDiMinusCurrent > adxEntryLevel && adxDiPlusCurrent > adxEntryLevel && adxDiPlusCurrent > adxDiMinusCurrent))) || adxPeriod == 0)) {

                        Transaction transaction = new Transaction(IEngine.OrderCommand.BUY, askBar.getClose());
                        transactionList.add(transaction);
                        transactionList.addAll(transaction.addWaitingOrders());

                        waitForAdx = false;

                    }

                }

                //SHORT
                if(getBarDirection(fourHourBar) == Direction.DOWN && getBarDirection(dailyBar) == Direction.DOWN) {

                    if(((checkForValidCross(lastCrossTime, bidBar.getTime()) && ((adxDiMinusCurrent > adxEntryLevel && adxDiPlusCurrent < adxEntryLevel) ||
                            (adxDiMinusCurrent > adxEntryLevel && adxDiPlusCurrent > adxEntryLevel && adxDiMinusCurrent > adxDiPlusCurrent))) ||
                            adxPeriod == 0)) {

                        Transaction transaction = new Transaction(IEngine.OrderCommand.SELL, bidBar.getClose());
                        transactionList.add(transaction);
                        transactionList.addAll(transaction.addWaitingOrders());

                        waitForAdx = false;
                    }
                }
            }
        }
    }

    private boolean hasAdxCrossed(double adxdi_plus_last, double adxdi_plus_current, double adxdi_minus_last, double adxdi_minus_current) {
        return (adxdi_minus_last < adxdi_plus_last && adxdi_minus_current > adxdi_plus_current) ||
                (adxdi_minus_last > adxdi_plus_last && adxdi_minus_current < adxdi_plus_current);
    }

    private boolean checkForValidCross(long last_cross, long current_time) {
        long diff = current_time - last_cross;
        long diff_mins = diff / 60;
        return diff_mins <= validCrossForMinutes;
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

    private Transaction findTransactionByOrder(IOrder order) {
        for(Transaction transaction : transactionList) {
            if(transaction != null && transaction.isValid() && transaction.order.getId().equals(order.getId())) return transaction;
        }
        return null;
    }

    private void deleteTransactionByOrder(IOrder order) {
        for(Iterator<Transaction> iterator = transactionList.iterator(); iterator.hasNext(); ) {
            Transaction transaction = iterator.next();
            if(transaction != null && !transaction.isValid() && transaction.order != null && transaction.order.getId().equals(order.getId())) {
                iterator.remove();
            }
        }
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
            try {
                if(order.getState() == IOrder.State.FILLED) {
                    order.close();
                }
            }
            catch(Exception e){
                e.printStackTrace();
            }
        }
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
            int precision = 5;
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

    protected void log(String log) {
        try {
            long time = history.getTick(instrument, 0).getTime();
            DateTime date = new DateTime(time);
            console.getOut().println("["+date.toString("YYYY-MM-DD HH:mm:ss")+"] "+ log);
        } catch (JFException e) {
            e.printStackTrace();
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

    protected String getLabel(Instrument instrument) {
        String label = instrument.name();
        label = label.substring(0, 2) + label.substring(3, 5);
        label = label + (tagCounter++);
        label = label.toLowerCase();
        return label;
    }

    public void onMessage(IMessage message) throws JFException {
        if(message.getType() == IMessage.Type.ORDER_FILL_OK) {
            log("Order fill ok with state "+message.getOrder().getState() + " and command " + message.getOrder().getOrderCommand());
            Transaction transaction = findTransactionByOrder(message.getOrder());
            if(transaction == null) return;
            Transaction parent = transaction.parent;
            if(parent == null) return;
            if(parent.nextProfit != null && parent.nextProfit.hashCode() == transaction.hashCode()) {

                if(parent.hedge != null) {
                    deleteTransactionByOrder(parent.hedge.order);
                    parent.hedge.closeOrder(1);
                    parent.hedge = null;
                }
            }
            else if(parent.hedge != null && parent.hedge.hashCode() == transaction.hashCode()) {

                if(parent.nextProfit != null) {
                    deleteTransactionByOrder(parent.nextProfit.order);

                    parent.nextProfit.closeOrder(1);
                    parent.nextProfit = null;
                }
            }

            transactionList.addAll(transaction.addWaitingOrders());
        }
        else if(message.getType() == IMessage.Type.ORDER_FILL_REJECTED) {
            deleteTransactionByOrder(message.getOrder());
        }
        else if(message.getType() == IMessage.Type.ORDER_SUBMIT_REJECTED) {
            deleteTransactionByOrder(message.getOrder());
        }
        else if(message.getType() == IMessage.Type.ORDER_CHANGED_OK || message.getType() == IMessage.Type.ORDER_CHANGED_REJECTED) {
            console.getErr().println(message);
        }
        else if(message.getType() == IMessage.Type.ORDER_CLOSE_OK) {
            if(message.getReasons().contains(IMessage.Reason.ORDER_CLOSED_BY_TP)) {
                log("Ordered closed by tp");
                /*
                if( (account.getEquity() / startMoney) > ((double) targetProfitPerc / 100d + 1)) {
                    console.getOut().println(account.getEquity() + " " + startMoney + " Closing all transactions because target profit is hit");
                    closeAll();

                    startMoney = this.account.getEquity();
                    waitForAdx = true;
                }
                else {
                    if(waitForAdx) return;

                    Transaction transaction = findTransactionByOrder(message.getOrder());
                    transaction.watchProfit(history.getTick(instrument, 0).getBid());
                    transactionList.add(transaction.nextProfit);
                }
                */

                deleteTransactionByOrder(message.getOrder());
            }
            else if(message.getReasons().contains(IMessage.Reason.ORDER_CLOSED_BY_SL)) {

                if(closeAllAfterLoss) closeAll();

            }
        }
    }

    public void onAccount(IAccount account) throws JFException {
        String accountCurrency = account.getCurrency().toString();
        double leverage = account.getLeverage();
        String accountId = account.getAccountId();
        double equity = account.getEquity();
        double useofLeverage = account.getUseOfLeverage();
        int overWeekendEndLeverage = account.getOverWeekEndLeverage();
        int marginCutLevel = account.getMarginCutLevel();
        boolean globalAccount = account.isGlobal();
    }

}
