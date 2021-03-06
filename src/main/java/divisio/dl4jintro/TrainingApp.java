package divisio.dl4jintro;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Main method to run Training with DL4J, handles command line parsing, logging,
 * saving & loading & resuming, kill signals
 */
public class TrainingApp {

    private static final Logger log = LoggerFactory.getLogger(TrainingApp.class);

    @Parameter(names = {"-h", "--help"},
               description = "Show usage info.",
               help = true)
    private boolean help;

    @Parameter(names = {"-e", "--epochs"},
               description = "Number of epochs to train.")
    private Integer epochs = 100;

    @Parameter(names = {"-v", "--validate-only"},
            description = "Only run validation on the most current model, cannot be combined with -e or -r")
    private boolean validateOnly = false;

    @Parameter(names = {"-r", "--resume"},
            description = "Resume training from last save, cannot be comined with -v")
    private boolean resume = false;

    @Parameter(names = {"--save-every-s"},
               description = "number of seconds between saves")
    private int saveEveryS = 5 * 60;

    @Parameter(names = {"--validate-every-s"},
               description = "number of seconds betwwen validations ")
    private int validateEveryS = 60;

    /**
     * the trainer we are training
     */
    private Trainer trainer;

    /**
     * the folder for logging, saved models etc.
     */
    private File workingFolder;

    /**
     * flag indicating if the application is still running
     */
    private volatile boolean running = true;

    /**
     * flag indicating if the application is still training
     */
    private volatile boolean training = true;

    /**
     * initializes working folder, logging & trainer
     */
    private void init() {
        //build the trainer we currently want to work with
        trainer = buildTrainer();
        //create a working folder with the trainer's class name
        workingFolder = new File(trainer.getClass().getSimpleName());

        //if we are not resuming the training, delete the old folder (if it exists)
        if (!resume && workingFolder.exists()) {
            try {
                log.info("Starting fresh training, deleting old working folder: " + workingFolder);
                FileUtils.deleteDirectory(workingFolder);
            } catch (final IOException ioe) {
                throw new RuntimeException("Could not delete existing folder " + workingFolder + " to start new training.", ioe);
            }
        }

        //create working dir if it does not exist
        //noinspection ResultOfMethodCallIgnored (we do not care if the dir already existed or was newly created)
        workingFolder.mkdirs();
        if (!workingFolder.isDirectory() || !workingFolder.canRead() || !workingFolder.canWrite()) {
            throw new RuntimeException("Cannot access " + workingFolder);
        }

        //init logging so our log output lands in the working dir
        initLogFile();

        log.info("Created trainer: " + trainer);
        //now either start or resume training
        if (resume) {
            final File lastSave = trainer.findLastSaveState(workingFolder);
            if (lastSave == null) {
                throw new RuntimeException("Cannot resume training, last save file not found.");
            }
            log.info("Found previous save: " + lastSave + ", resuming training.");
            trainer.load(lastSave);
        } else {
            trainer.init();
        }
    }

    /**
     * Builds the trainer we want to train, just replace with a different trainer for comparison
     */
    private Trainer buildTrainer() {
        return new BinaryAndTrainer();
    }

    /**
     * save the current training state
     */
    private void save() {
        log.info("Saving...");
        final File saveFile = trainer.save(workingFolder);
        log.info("Saved state to: " + saveFile);
    }

    /**
     * triggers validation
     */
    private void validate() {
        log.info("Validating...");
        trainer.validate();
    }

    /**
     * Runs training for the number of epochs defined as console argument
     */
    private void train() {
        //last timestamp in millis when state was saved
        long lastSave = System.currentTimeMillis();
        // last timestamp in millis when validation was performed
        long lastValidation = System.currentTimeMillis();
        for (int epochCount = 0; epochCount < epochs; ++epochCount) {
            final int currentEpoch = trainer.startEpoch();
            log.info("Starting epoch " + currentEpoch);
            while (trainer.train() && running) {
                final long now = System.currentTimeMillis();
                final long deltaSave = (now - lastSave) / 1000;
                final long deltaValidation = (now - lastValidation) / 1000;
                if (deltaSave > saveEveryS) {
                    save();
                    lastSave = System.currentTimeMillis();
                }
                if (deltaValidation > validateEveryS) {
                    validate();
                    lastValidation = System.currentTimeMillis();
                }
            }
            //stop training if the shutdown hook was called
            if (!running) { break; }
        }
        //if we finish training normally, validate once more
        if (running) {
            log.info("Training finished, running final validation.");
            validate();
        } else {
            //otherwise log that we were interrupted
            log.info("Training interrupted.");
        }
        //signal that we are done training
        training = false;
    }

    /**
     * Called when the VM exits, tries to save result. By moving the final saving of results here, we always save our
     * work. This way, we can interrupt a long running training and not loose all the work.
     */
    private void shutdown() {
        //signal that we are not running anymore
        running = false;
        //wait for training to finish gracefully
        while (training)  {
            try {
                Thread.sleep(100);
            } catch (final InterruptedException ie) { /* we cannot wait anymore */ }
        }
        //if training had time to shutdown properly, save state so our work isn't lost
        if (!training) {
            save();
        }
    }

    /**
     * Additionally routes logging to a log file in the working folder.
     * Used to write each training log into it's own folder, so we retain a copy of all training runs.
     */
    @SuppressWarnings("unchecked")
    private void initLogFile() {
        final File logFile = new File(workingFolder, "training.log");

        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        final FileAppender fileAppender = new FileAppender();
        fileAppender.setContext(loggerContext);
        fileAppender.setName("file");
        fileAppender.setFile(logFile.getAbsolutePath());

        final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern("%date{ISO8601} %logger{24} %level - %msg%n");
        encoder.start();

        fileAppender.setEncoder(encoder);
        fileAppender.start();

        //append new logger as additional appender for root logging
        final ch.qos.logback.classic.Logger rootLogger =
                loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
        rootLogger.addAppender(fileAppender);

        log.info("----------------------------- new run ------------------------------");
        log.info("Logging to: " + logFile);
    }

    public static void main( String[] args ) {
        // create instance of our Console Application
        final TrainingApp app = new TrainingApp();

        // parse command line params
        final JCommander commander = JCommander.newBuilder().addObject(app).build();
        try {
            commander.parse(args);
        } catch (final ParameterException pe) {
            //thrown if the given arguments are invalid - print the error message, print usage instructions & exit.
            System.out.println(pe.getMessage());
            commander.usage();
            System.exit(-1);
            return;
        }
        if (app.help) {
            commander.usage();
            System.exit(0);
            return;
        }

        //init application
        app.init();

        //either train or validate, depending on command line args
        if (app.validateOnly) {
            app.validate();
        } else { //training
            //make sure to save on shutdown when we train
            Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
            app.train();
        }
    }
}
