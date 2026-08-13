package com.github.obhen233.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * CLI implementation of TerminalIO backed by JLine 3.x.
 * Runs the readLine loop in a dedicated daemon thread.
 */
public class JlineTerminalIO implements TerminalIO {

    private static final Logger logger = LoggerFactory.getLogger(JlineTerminalIO.class);

    private final String prompt;
    private final String historyFilePath;

    private Terminal terminal;
    private LineReader reader;
    private Thread inputThread;
    private volatile boolean running = false;

    private Consumer<String> lineHandler;
    private Runnable interruptHandler;
    private Consumer<Status> statusHandler;

    public JlineTerminalIO() {
        this("> ", null);
    }

    public JlineTerminalIO(String prompt) {
        this(prompt, null);
    }

    public JlineTerminalIO(String prompt, String historyFilePath) {
        this.prompt = prompt;
        this.historyFilePath = historyFilePath;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        try {
            terminal = TerminalBuilder.builder()
                    .name("diatom")
                    .build();

            LineReaderBuilder readerBuilder = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .option(LineReader.Option.BRACKETED_PASTE, true);

            if (historyFilePath != null) {
                java.nio.file.Path histFile = java.nio.file.Paths.get(historyFilePath);
                try {
                    java.nio.file.Files.createDirectories(histFile.getParent());
                } catch (IOException e) {
                    logger.warn("Failed to create history directory", e);
                }
                readerBuilder.variable(LineReader.HISTORY_FILE, histFile.toFile());
            }

            reader = readerBuilder.build();

            running = true;
            inputThread = new Thread(this::inputLoop, "jline-input");
            inputThread.setDaemon(true);
            inputThread.start();

            logger.info("JlineTerminalIO started");
        } catch (IOException e) {
            logger.error("Failed to initialize JLine terminal", e);
            throw new RuntimeException("Failed to initialize terminal", e);
        }
    }

    private void inputLoop() {
        InputStream termInput = terminal.input();
        while (running) {
            String line;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) {
                Thread.interrupted();
                // Ctrl+C — trigger interrupt
                if (interruptHandler != null) {
                    interruptHandler.run();
                }
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null) {
                break;
            }

            // Fire line handler
            if (lineHandler != null && running) {
                try {
                    lineHandler.accept(line);
                } catch (Exception ex) {
                    logger.error("Line handler error", ex);
                }
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (inputThread != null) {
            inputThread.interrupt();
        }
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException e) {
                logger.warn("Error closing terminal", e);
            }
        }
        logger.info("JlineTerminalIO stopped");
    }

    @Override
    public void write(String text) {
        if (terminal != null) {
            synchronized (this) {
                terminal.writer().print(text);
                terminal.writer().flush();
            }
        }
    }

    @Override
    public void writeLine(String text) {
        write(text + System.lineSeparator());
    }

    @Override
    public void setLineHandler(Consumer<String> handler) {
        this.lineHandler = handler;
    }

    @Override
    public void setInterruptHandler(Runnable handler) {
        this.interruptHandler = handler;
    }

    @Override
    public void setStatusHandler(Consumer<Status> handler) {
        this.statusHandler = handler;
    }

    /** Get the underlying JLine Terminal (for advanced usage). */
    public Terminal getTerminal() {
        return terminal;
    }

    /** Get the underlying JLine LineReader (for advanced usage). */
    public LineReader getReader() {
        return reader;
    }
}
