/*
 * Copyright (c) 2009 Dukascopy (Suisse) SA. All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * -Redistribution of source code must retain the above copyright notice, this
 *  list of conditions and the following disclaimer.
 *
 * -Redistribution in binary form must reproduce the above copyright notice,
 *  this list of conditions and the following disclaimer in the documentation
 *  and/or other materials provided with the distribution.
 * 
 * Neither the name of Dukascopy (Suisse) SA or the names of contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES, INCLUDING
 * ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 * OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. DUKASCOPY (SUISSE) SA ("DUKASCOPY")
 * AND ITS LICENSORS SHALL NOT BE LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE
 * AS A RESULT OF USING, MODIFYING OR DISTRIBUTING THIS SOFTWARE OR ITS
 * DERIVATIVES. IN NO EVENT WILL DUKASCOPY OR ITS LICENSORS BE LIABLE FOR ANY LOST
 * REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL,
 * INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY
 * OF LIABILITY, ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE,
 * EVEN IF DUKASCOPY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 */
package main.java.robot;

import com.dukascopy.api.*;
import com.dukascopy.api.feed.FeedDescriptor;
import com.dukascopy.api.feed.IFeedDescriptor;
import com.dukascopy.api.system.ISystemListener;
import com.dukascopy.api.system.ITesterClient;
import com.dukascopy.api.system.TesterFactory;
import com.dukascopy.api.system.tester.*;
import main.java.jforex.TooSimple;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import static com.dukascopy.api.DataType.*;
import static com.dukascopy.api.Instrument.*;
import static com.dukascopy.api.PriceRange.*;
import static com.dukascopy.api.TickBarSize.*;


/**
 * This small program demonstrates how to initialize Dukascopy tester and start a strategy in GUI mode
 */
@SuppressWarnings("serial")
public class GUIModeChartTypes extends JFrame implements ITesterUserInterface, ITesterExecution {
    private static final Logger LOGGER = LoggerFactory.getLogger(GUIModeChartTypes.class);

    private final int frameWidth = 1000;
    private final int frameHeight = 600;
    private final int controlPanelHeight = 40;

    private JPanel currentChartPanel = null;
    private ITesterExecutionControl executionControl = null;

    private JPanel controlPanel = null;
    private JButton startStrategyButton = null;
    private JButton pauseButton = null;
    private JButton continueButton = null;
    private JButton cancelButton = null;

    private FeedDescriptorPanel feedDescriptorPanel;
    private ITesterChartController chartController;
    private IChart currentChart;
    private IFeedDescriptor feedDescriptor = new FeedDescriptor();

    //url of the DEMO jnlp
    private static String jnlpUrl = "https://www.dukascopy.com/client/demo/jclient/jforex.jnlp";
    //user name
    private static String userName = "DEMO2FSehY";
    //password
    private static String password = "FSehY";

    public GUIModeChartTypes(){
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        getContentPane().setLayout(new BoxLayout(getContentPane(), BoxLayout.Y_AXIS));
    }

    @Override
    public void setChartPanels(Map<IChart, ITesterGui> chartPanels) {
        if(chartPanels != null && chartPanels.size() > 0){

            IChart chart = chartPanels.keySet().iterator().next();

            //Note we assume we work with only one chart;
            currentChart = chart;
            chartController = chartPanels.get(chart).getTesterChartController();

            setTitle("Chart type example");

            chartController.setFeedDescriptor(feedDescriptor);
            JPanel chartPanel = chartPanels.get(chart).getChartPanel();
            addChartPanel(chartPanel);
        }
    }

    @Override
    public void setExecutionControl(ITesterExecutionControl executionControl) {
        this.executionControl = executionControl;
    }

    public void startStrategy() throws Exception {
        //get the instance of the IClient interface
        final ITesterClient client = TesterFactory.getDefaultInstance();
        //set the listener that will receive system events
        client.setSystemListener(new ISystemListener() {


            @Override
            public void onStart(long processId) {
                LOGGER.info("Strategy started: " + processId);
                updateButtons();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }


            }

            @Override
            public void onStop(long processId) {
                LOGGER.info("Strategy stopped: " + processId);
                resetButtons();

                File reportFile = new File("C:\\report.html");
                try {
                    client.createReport(processId, reportFile);
                } catch (Exception e) {
                    LOGGER.error(e.getMessage(), e);
                }
                if (client.getStartedStrategies().size() == 0) {
                    //Do nothing
                }
            }

            @Override
            public void onConnect() {
                LOGGER.info("Connected");
            }

            @Override
            public void onDisconnect() {
                //tester doesn't disconnect
            }
        });

        DateTimeFormatter dtf = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm");
        DateTime from = DateTime.parse("2015-06-10 00:00", dtf);
        DateTime to =   DateTime.parse("2015-06-10 11:00", dtf);

        client.setDataInterval(Period.TICK, OfferSide.BID,
                ITesterClient.InterpolationMethod.OPEN_TICK,
                from.getMillis(), to.getMillis());

        LOGGER.info("Connecting...");
        //connect to the server using jnlp, user name and password
        //connection is needed for data downloading
        client.connect(jnlpUrl, userName, password);

        //wait for it to connect
        int i = 10; //wait max ten seconds
        while (i > 0 && !client.isConnected()) {
            Thread.sleep(1000);
            i--;
        }
        if (!client.isConnected()) {
            LOGGER.error("Failed to connect Dukascopy servers");
            System.exit(1);
        }

        //set instruments that will be used in testing
        final Set<Instrument> instruments = new HashSet<Instrument>();
        instruments.add(Instrument.EURUSD);

        LOGGER.info("Subscribing instruments...");
        client.setSubscribedInstruments(instruments);
        //setting initial deposit
        client.setInitialDeposit(Instrument.EURUSD.getSecondaryCurrency(), 50000);
        //load data
        LOGGER.info("Downloading data");
        Future<?> future = client.downloadData(null);
        //wait for downloading to complete
        future.get();
        //start the strategy
        LOGGER.info("Starting strategy");

        client.startStrategy(
                new TooSimple(),
                new LoadingProgressListener() {
                    @Override
                    public void dataLoaded(long startTime, long endTime, long currentTime, String information) {
                        LOGGER.info("dataLoaded " + information);
                    }

                    @Override
                    public void loadingFinished(boolean allDataLoaded, long startTime, long endTime, long currentTime) {
                        LOGGER.info("loadingFinished " + allDataLoaded);
                    }

                    @Override
                    public boolean stopJob() {
                        return false;
                    }
                }, this, this
        );
        //now it's running

        //In the current implementation it takes prolonged time for some chart types to load (e.g. range bars, renko),
        //so we hold up execution for maximum 5 minutes till the chart gets loaded.
        //For quicker loading please manually decrease chart's horizontal scale - it gets printed every second.
        Runnable r2 = new Runnable() {
            public void run() {
                try {
                    int waitTimeSecs = 300;
                    LOGGER.info("Pause execution for max " + waitTimeSecs + " secs till chart gets loaded. " +
                            "For quicker loading please decrease currentChart.getBarsCount() - manually decrease chart's horizontal scale.");
                    executionControl.pauseExecution();
                    updateButtons();
                    long startTime = System.currentTimeMillis();
                    try {
                        while ((currentChart == null || Math.abs(currentChart.priceMin(0)) < 0.00001) && System.currentTimeMillis() - startTime < waitTimeSecs * 1000) {
                            if (currentChart != null){
                                int secsLeft = (int) (waitTimeSecs - (System.currentTimeMillis() - startTime) /1000);
                                LOGGER.info(String.format("Min price=%.5f, bar count on chart=%s, time left=%s secs", currentChart.priceMin(0), currentChart.getBarsCount(), secsLeft));
                            }
                            Thread.sleep(1000);
                        }
                    } catch (Exception e2) {
                        LOGGER.error(e2.getMessage(), e2);
                        e2.printStackTrace();
                    }

                    LOGGER.info("Chart loaded after " + ((System.currentTimeMillis() - startTime)/1000) + " secs. Please press continue.");
                } catch (Exception e2) {
                    LOGGER.error(e2.getMessage(), e2);
                    e2.printStackTrace();
                }
            }
        };
        Thread t2 = new Thread(r2);
        t2.start();

    }

    private class FeedDescriptorPanel extends JPanel{

        private JComboBox comboBoxDataType;
        private JComboBox comboBoxInstrument;
        private JComboBox comboBoxOfferSide;
        private JComboBox comboBoxFilter;
        private JComboBox comboBoxPeriod;
        private JComboBox comboBoxPriceRange;
        private JComboBox comboBoxReversalAmount;
        private JComboBox comboBoxTickBarSize;
        private JButton buttonApplyChanges;

        public FeedDescriptorPanel(){

            this.setLayout(new FlowLayout(FlowLayout.LEFT));

            comboBoxDataType = setupComboBox(DataType.values(),"Data type", DataType.TIME_PERIOD_AGGREGATION);
            comboBoxInstrument = setupComboBox(new Instrument [] {EURUSD, USDJPY, USDCAD}, "Instrument", EURUSD);
            comboBoxOfferSide = setupComboBox(OfferSide.values(), "Offer Side", OfferSide.BID);
            comboBoxFilter = setupComboBox(Filter.values(), "Filter", Filter.NO_FILTER);
            comboBoxPeriod = setupComboBox(Period.values(), "Period", Period.TEN_MINS);
            comboBoxPriceRange = setupComboBox(new PriceRange [] {ONE_PIP, TWO_PIPS, THREE_PIPS, FOUR_PIPS, FIVE_PIPS, SIX_PIPS}, "Price Range", TWO_PIPS);
            comboBoxReversalAmount = setupComboBox(new ReversalAmount [] {ReversalAmount.ONE, ReversalAmount.TWO, ReversalAmount.THREE}, "Reversal Amount", ReversalAmount.TWO);
            comboBoxTickBarSize = setupComboBox(new TickBarSize [] {TWO, THREE, FOUR, FIVE}, "Tick Bar Size", THREE);

            add(comboBoxDataType);
            add(comboBoxPeriod);
            add(comboBoxInstrument);
            add(comboBoxOfferSide);
            add(comboBoxFilter);
            add(comboBoxPriceRange);
            add(comboBoxReversalAmount);
            add(comboBoxTickBarSize);

            buttonApplyChanges = new JButton("Apply changes");
            buttonApplyChanges.addActionListener(new ActionListener(){

                @Override
                public void actionPerformed(ActionEvent e) {
                    updateFeedDesciptor();

                }

            });

            add(buttonApplyChanges);

            updateFeedDesciptor();
            updateComboBoxes();
        }

        private JComboBox setupComboBox(final Object items[], String name, Object defaultValue){
            JComboBox comboBox = new JComboBox(items);
            comboBox.setSelectedItem(defaultValue);
            comboBox.addActionListener(new ActionListener (){
                @Override
                public void actionPerformed(ActionEvent e) {
                    updateComboBoxes();
                }

            });
            comboBox.setToolTipText(name);
            return comboBox;
        }

        private void updateComboBoxes(){

            DataType dataType = (DataType)comboBoxDataType.getSelectedItem();

            //visibility conditions according to IFeedDescription interface documentation
            comboBoxDataType.setVisible(true);
            comboBoxInstrument.setVisible(true);
            comboBoxOfferSide.setVisible(dataType != TICKS);
            comboBoxFilter.setVisible(dataType == TIME_PERIOD_AGGREGATION);
            comboBoxPeriod.setVisible(dataType == TIME_PERIOD_AGGREGATION);
            comboBoxPriceRange.setVisible(dataType == PRICE_RANGE_AGGREGATION
                    || dataType == POINT_AND_FIGURE
                    || dataType == RENKO);
            comboBoxReversalAmount.setVisible(dataType == POINT_AND_FIGURE);
            comboBoxTickBarSize.setVisible(dataType == TICK_BAR);

        }

        private void updateFeedDesciptor(){

            feedDescriptor.setDataType((DataType)comboBoxDataType.getSelectedItem());
            feedDescriptor.setInstrument((Instrument)comboBoxInstrument.getSelectedItem());
            feedDescriptor.setPeriod((Period)comboBoxPeriod.getSelectedItem());
            feedDescriptor.setOfferSide((OfferSide)comboBoxOfferSide.getSelectedItem());
            feedDescriptor.setFilter((Filter)comboBoxFilter.getSelectedItem());
            feedDescriptor.setPriceRange((PriceRange)comboBoxPriceRange.getSelectedItem());
            feedDescriptor.setReversalAmount((ReversalAmount)comboBoxReversalAmount.getSelectedItem());
            feedDescriptor.setTickBarSize((TickBarSize)comboBoxTickBarSize.getSelectedItem());

            if(chartController != null)
                chartController.setFeedDescriptor(feedDescriptor);
        }

    }

    /**
     * Center a frame on the screen
     */
    private void centerFrame(){
        Toolkit tk = Toolkit.getDefaultToolkit();
        Dimension screenSize = tk.getScreenSize();
        int screenHeight = screenSize.height;
        int screenWidth = screenSize.width;
        setSize(screenWidth / 4, screenHeight / 2);
        setLocation(200, screenHeight / 4);
    }

    /**
     * Add chart panel to the frame
     * @param chartPanel panel
     */
    private void addChartPanel(JPanel chartPanel){
        removecurrentChartPanel();

        this.currentChartPanel = chartPanel;
        chartPanel.setPreferredSize(new Dimension(frameWidth, frameHeight - controlPanelHeight));
        chartPanel.setMinimumSize(new Dimension(frameWidth, 200));
        chartPanel.setMaximumSize(new Dimension(Short.MAX_VALUE, Short.MAX_VALUE));
        getContentPane().add(chartPanel);
        this.validate();
        chartPanel.repaint();
    }

    /**
     * Add buttons to start/pause/continue/cancel actions
     */
    private void addControlPanel(){

        controlPanel = new JPanel();
        FlowLayout flowLayout = new FlowLayout(FlowLayout.LEFT);
        controlPanel.setLayout(flowLayout);
        controlPanel.setPreferredSize(new Dimension(frameWidth, controlPanelHeight));
        controlPanel.setMinimumSize(new Dimension(frameWidth, controlPanelHeight));
        controlPanel.setMaximumSize(new Dimension(Short.MAX_VALUE, controlPanelHeight));

        startStrategyButton = new JButton("Start strategy");
        startStrategyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startStrategyButton.setEnabled(false);
                Runnable r = new Runnable() {
                    public void run() {
                        try {
                            startStrategy();
                        } catch (Exception e2) {
                            LOGGER.error(e2.getMessage(), e2);
                            e2.printStackTrace();
                            resetButtons();
                        }
                    }
                };
                Thread t = new Thread(r);
                t.start();


            }
        });

        pauseButton = new JButton("Pause");
        pauseButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if(executionControl != null){
                    executionControl.pauseExecution();
                    updateButtons();
                }
            }
        });

        continueButton = new JButton("Continue");
        continueButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if(executionControl != null){
                    executionControl.continueExecution();
                    updateButtons();
                }
            }
        });

        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if(executionControl != null){
                    executionControl.cancelExecution();
                    updateButtons();
                }
            }
        });

        controlPanel.add(startStrategyButton);
        controlPanel.add(pauseButton);
        controlPanel.add(continueButton);
        controlPanel.add(cancelButton);
        getContentPane().add(controlPanel);
        feedDescriptorPanel = new FeedDescriptorPanel();
        getContentPane().add(feedDescriptorPanel);

        pauseButton.setEnabled(false);
        continueButton.setEnabled(false);
        cancelButton.setEnabled(false);
    }

    private void updateButtons(){
        if(executionControl != null){
            startStrategyButton.setEnabled(executionControl.isExecutionCanceled());
            pauseButton.setEnabled(!executionControl.isExecutionPaused() && !executionControl.isExecutionCanceled());
            cancelButton.setEnabled(!executionControl.isExecutionCanceled());
            continueButton.setEnabled(executionControl.isExecutionPaused());
        }
    }

    private void resetButtons(){
        startStrategyButton.setEnabled(true);
        pauseButton.setEnabled(false);
        continueButton.setEnabled(false);
        cancelButton.setEnabled(false);
    }

    private void removecurrentChartPanel(){
        if(this.currentChartPanel != null){
            try {
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        GUIModeChartTypes.this.getContentPane().remove(GUIModeChartTypes.this.currentChartPanel);
                        GUIModeChartTypes.this.getContentPane().repaint();
                    }
                });
            } catch (Exception e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
    }

    public void showChartFrame(){
        setSize(frameWidth, frameHeight);
        centerFrame();
        addControlPanel();
        setVisible(true);
    }

    public static void main(String[] args) throws Exception {
        GUIModeChartTypes testerMainGUI = new GUIModeChartTypes();
        testerMainGUI.showChartFrame();
    }
}