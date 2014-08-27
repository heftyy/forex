package main.java.jforex;

import com.dukascopy.api.*;

import java.text.DecimalFormat;
import java.util.Date;

/**
 * Created with IntelliJ IDEA.
 * User: Szymon
 * Date: 26.06.13
 */
public class Alligator implements IStrategy{

    private IEngine engine = null;
    private IIndicators indicators = null;
    private IHistory history = null;
    private int tagCounter = 0;
    private IConsole console;

    @Configurable("Starting lots")
    public double lots = 0.001;
    @Configurable("Take profit[pips]")
    public int takeProfitPips = 15;
    @Configurable("Stop loss[pips]")
    public int stopLossPips = 1;
    @Configurable("Standard deviation margin[0.00001]")
    public double standardDeviationMargin = 1;
    @Configurable("Awesome oscillator margin[0.0001]")
    public double awesomeOscillatorMargin = 3;
    @Configurable("Awesome oscillator Faster Ma Period")
    public int AwesomeOscillatorFasterMaPeriod = 5;
    @Configurable("Awesome oscillator Slower Ma Period")
    public int AwesomeOscillatorSlowerMaPeriod = 34;
    @Configurable("Fractal lines bars on sides")
    public int fractalLinesBarsOnSides = 5;
    @Configurable("Period")
    public Period period = Period.FIVE_MINS;

    private int precision = 5;
    private boolean checkForTrade = false;
    private boolean tradeDone = false;

    private final static int JAW_TIME_PERIOD   = 13;
    private final static int TEETH_TIME_PERIOD =  8;
    private final static int LIPS_TIME_PERIOD  =  5;

    double[] fractalLines;
    double[] alligator;
    double[] oscillator;

	private IBar bidBar = null, askBar = null;
	private Instrument instrument = Instrument.EURUSD;

    public static class Statistics
    {
        double[] data;
        double size;

        public Statistics(double[] data)
        {
            this.data = data;
            size = data.length;
        }

        double getMean()
        {
            double sum = 0.0;
            for(double a : data) {
                sum += a;
            }
            return sum/size;
        }

        double getVariance()
        {
            double mean = getMean();
            double temp = 0;
            for(double a :data) {
                temp += (mean-a)*(mean-a);
            }
            return temp/size;
        }

        double getStdDev()
        {
            return Math.sqrt(getVariance());
        }
    }

    public void onStart(IContext context) throws JFException {
        engine = context.getEngine();
        indicators = context.getIndicators();
        history = context.getHistory();
        console = context.getConsole();
        alligator = new double[3];
        oscillator = new double[2];
        fractalLines = new double[2];
        standardDeviationMargin *= 0.00001;
        awesomeOscillatorMargin *= 0.0001;
	    this.bidBar = context.getHistory().getBar(instrument, period, OfferSide.BID, 1);
	    this.askBar = context.getHistory().getBar(instrument, period, OfferSide.ASK, 1);
        console.getOut().println("Started");
    }

    public void onStop() throws JFException {
        closeAll();
        console.getOut().println("Stopped");
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        /*
	    if (checkForTrade && !tradeDone) {
            IEngine.OrderCommand command = checkFractalLinesBreak(instrument, tick, fractalLines);
            if (command == IEngine.OrderCommand.BUY) {
                double stopLoss = fractalLines[1]-stopLossPips*instrument.getPipValue();
                double takeProfit = tick.getBid()+takeProfitPips*instrument.getPipValue();
                IOrder order = openOrder(instrument, command, getLots(instrument), stopLoss, takeProfit);
                if(order != null) {
                    tradeDone = true;
                }
            } else if (command == IEngine.OrderCommand.SELL) {
                double stopLoss = fractalLines[0]+stopLossPips*instrument.getPipValue();
                double takeProfit = tick.getAsk()-takeProfitPips*instrument.getPipValue();
                IOrder order = openOrder(instrument, command, getLots(instrument), stopLoss, takeProfit);
                if(order != null) {
                    tradeDone = true;
                }
            }
        }
	    */
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (period == this.period && instrument.equals(Instrument.EURUSD))  {
	        this.bidBar = bidBar;
	        this.askBar = askBar;
            tradeDone = false;
            oscillator = indicators.awesome(instrument, period, OfferSide.BID, IIndicators.AppliedPrice.CLOSE, AwesomeOscillatorFasterMaPeriod, IIndicators.MaType.SMA, AwesomeOscillatorSlowerMaPeriod, IIndicators.MaType.SMA, 0);
            fractalLines = indicators.fractalLines(instrument, period, OfferSide.BID, fractalLinesBarsOnSides, 0);
            alligator[0] = indicators.alligator(instrument, period, OfferSide.BID, IIndicators.AppliedPrice.MEDIAN_PRICE, JAW_TIME_PERIOD, TEETH_TIME_PERIOD, LIPS_TIME_PERIOD, 8)[0];
            alligator[1] = indicators.alligator(instrument, period, OfferSide.BID, IIndicators.AppliedPrice.MEDIAN_PRICE, JAW_TIME_PERIOD, TEETH_TIME_PERIOD, LIPS_TIME_PERIOD, 5)[1];
            alligator[2] = indicators.alligator(instrument, period, OfferSide.BID, IIndicators.AppliedPrice.MEDIAN_PRICE, JAW_TIME_PERIOD, TEETH_TIME_PERIOD, LIPS_TIME_PERIOD, 3)[2];
//            logArray(alligator, "alligator", instrument);
//            logArray(oscillator, "oscillator", instrument);
//            logArray(fractalLines, "fractalLines", instrument);
            boolean alligatorReady = checkAlligator(instrument, alligator, fractalLines);
//            boolean oscillatorReady = checkOscillator(instrument, oscillator);
            //console.getOut().println("["+new Date(history.getBar(instrument, period, OfferSide.BID, 0).getTime())+"]"+"ALLIGATOR READY = "+alligatorReady+" OSCILLATOR READY = "+oscillatorReady);
            if (alligatorReady) {
                checkForTrade = true;
            } else {
                checkForTrade = false;
            }
	        if (checkForTrade && positionsTotal(instrument) == 0) {
		        IEngine.OrderCommand command = checkFractalLinesBreak(instrument, fractalLines);
		        if (command == IEngine.OrderCommand.BUY) {
			        double stopLoss = fractalLines[1]-stopLossPips*instrument.getPipValue();
			        double takeProfit = bidBar.getClose()+takeProfitPips*instrument.getPipValue();
			        IOrder order = openOrder(instrument, command, getLots(instrument), stopLoss, takeProfit);
			        if(order != null) {
				        tradeDone = true;
			        }
		        } else if (command == IEngine.OrderCommand.SELL) {
			        double stopLoss = fractalLines[0]+stopLossPips*instrument.getPipValue();
			        double takeProfit = askBar.getClose()-takeProfitPips*instrument.getPipValue();
			        IOrder order = openOrder(instrument, command, getLots(instrument), stopLoss, takeProfit);
			        if(order != null) {
				        tradeDone = true;
			        }
		        }
	        }
        }
    }

    protected boolean checkAlligator(Instrument instrument, double[] alligator, double[] fractalLines) throws JFException {
        Statistics statistics = new Statistics(alligator);
        double standardDeviation = statistics.getStdDev();
        //console.getOut().println("Standard deviation = "+standardDeviation+" "+new Date(history.getBar(instrument,period, OfferSide.BID, 0).getTime()));
        if (standardDeviation < standardDeviationMargin) {
            boolean alligatorBetweenFractalLines = true;
            double fractalLinesHigh = fractalLines[0];
            double fractalLinesLow = fractalLines[1];
            for(int i = 0; i < alligator.length; i++) {
                if(alligator[i] >= fractalLinesLow && alligator[i] <= fractalLinesHigh) {
                    continue;
                }
                alligatorBetweenFractalLines = false;
                break;
            }
            return alligatorBetweenFractalLines;
        }
        return false;
    }

    protected boolean checkOscillator(Instrument instrument, double[] oscillator) throws JFException  {
        double positive = oscillator[1];
        double negative = oscillator[2];
        if(positive != 0.0d && positive > -awesomeOscillatorMargin) {
//            console.getOut().println("Positive = "+formatDouble(positive)+" "+new Date(history.getBar(instrument,period, OfferSide.BID, 0).getTime()));
            return true;
        }
        if(negative != 0.0d && negative < awesomeOscillatorMargin) {
//            console.getOut().println("Negative = "+formatDouble(negative)+" "+new Date(history.getBar(instrument,period, OfferSide.BID, 0).getTime()));
            return true;
        }
        return false;
    }

    protected IEngine.OrderCommand checkFractalLinesBreak(Instrument instrument, double[] fractalLines) throws JFException  {
        double fractalLinesHigh = fractalLines[0];
        double fractalLinesLow = fractalLines[1];

        if(askBar.getClose() > fractalLinesHigh) {
            return IEngine.OrderCommand.BUY;
        }
        if(bidBar.getClose() < fractalLinesLow) {
            return IEngine.OrderCommand.SELL;
        }
        return null;
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

    protected void closeAll() throws JFException {
        for (IOrder order : engine.getOrders()) {
            order.close();
        }
    }

    protected boolean checkForClose(Instrument instrument) throws  JFException{
        return false;
    }

    protected IOrder openOrder(Instrument instrument, IEngine.OrderCommand command, double lot, double stopLoss, double takeProfit) throws JFException {
        IOrder order = null;
        if ( command == IEngine.OrderCommand.BUY ) {
            order = engine.submitOrder(getLabel(instrument), instrument, IEngine.OrderCommand.BUY, lot, 0, 0, stopLoss, takeProfit);
        } else if ( command == IEngine.OrderCommand.SELL ) {
            order = engine.submitOrder(getLabel(instrument), instrument, IEngine.OrderCommand.SELL, lot, 0, 0, stopLoss, takeProfit);
        }
        if ( order != null ) {
            System.out.println("Order placed for = "+lot*1000000/100+" eur");
        }
        return order;
    }

    protected double getLots(Instrument instrument) throws JFException {
        double lot = 0.001d;
        return lot;
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

    protected String formatDouble(double num) {
        if(num == Double.NaN) {
            return "NaN";
        }
        return new DecimalFormat("#.#######").format(num);
    }

    protected void logArray(double[] array, String name, Instrument instrument) throws JFException {
        String out = "["+new Date(history.getBar(instrument, period, OfferSide.BID, 0).getTime())+"]"+name+" \t";
        for(int i = 0; i < array.length; i++) {
            out += formatDouble(array[i])+" ";
        }
        console.getOut().println(out);
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
        if(message.getType() == IMessage.Type.ORDER_CLOSE_OK){
            double profitLoss = message.getOrder().getProfitLossInAccountCurrency();
            console.getOut().println("["+new Date(message.getCreationTime())+"]Order closed with profit/loss = "+profitLoss);
        }
        //System.out.println(message);
    }

    public void onAccount(IAccount account) throws JFException {
        //System.out.println(account);
    }

}