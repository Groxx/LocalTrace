package com.example

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LocalTrace {
    public static void beginSection(String name) {
        INSTANCE.begin(name);
    }

    public static void endSection() {
        INSTANCE.end();
    }

    public static void init(Context context) {
       INSTANCE = new LocalTrace(context);
    }

    private static final double NANOS_PER_TIMESTAMP = 1000d * 1000d * 1000d;

    private static LocalTrace INSTANCE = null;

    private final BufferedWriter output;
    private final Runnable flushable;
    private final Handler flusher;
    private final HandlerThread flushThread;
    private final List<CharSequence> logs = new ArrayList<CharSequence>();
    private final int cores;
    private final NumberFormat timestampFormatter;
    private final NumberFormat coreFormatter;

    private LocalTrace(Context context) {
        cores = Runtime.getRuntime().availableProcessors();
        timestampFormatter = DecimalFormat.getNumberInstance(Locale.US);
        timestampFormatter.setMaximumFractionDigits(6);
        timestampFormatter.setMinimumFractionDigits(6);
        timestampFormatter.setGroupingUsed(false);
        coreFormatter = NumberFormat.getNumberInstance();
        coreFormatter.setMaximumFractionDigits(0);
        coreFormatter.setMinimumIntegerDigits(3);

        String filename = context.getFilesDir().getAbsolutePath() + "/trace-" + System.currentTimeMillis() + ".trace";
        try {
            output = new BufferedWriter(new FileWriter(new File(filename)));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        flushThread = new HandlerThread("Flusher", HandlerThread.NORM_PRIORITY);
        flushThread.start();
        flusher = new Handler(flushThread.getLooper());
        flushable = new Flush();
        logs.add(preamble);
        postFlush();
    }

    private void begin(String name) {
        StringBuilder sb = buildLine(name, true);
        synchronized (logs) {
            logs.add(sb);
        }
    }

    private void end() {
        StringBuilder sb = buildLine(null, false);
        synchronized (logs) {
            logs.add(sb);
        }
    }

    private StringBuilder buildLine(String name, boolean isBeginning) {
        StringBuilder sb = new StringBuilder();
        Thread current = Thread.currentThread();
        long now = System.nanoTime();
        sb.append(current.getName());
        sb.append("-");
        sb.append(current.getId());
        sb.append(" ");
        sb.append("(1337)"); // a fake process ID
        sb.append(" ");
        // a fake CPU core ID, since there's no API.  it seems to be ignored anyway.
        sb.append("[").append(coreFormatter.format(current.getId() % cores)).append("]");
        sb.append(" ...1 "); // "preempt-depth" is always on, most others are not (ever).  fake it.
        sb.append(timestampFormatter.format(now / NANOS_PER_TIMESTAMP));
        sb.append(": tracing_mark_write: ");
        if (isBeginning) {
            // beginning shows process ID again
            sb.append("B|1337|").append(name);
        } else {
            sb.append("E");
        }
        sb.append("\\n\\\n");
        return sb;
    }

    private void flush() {
        List<CharSequence> copy;
        synchronized (logs) {
            if (logs.isEmpty()) {
                postFlush();
                return;
            }
            copy = new ArrayList<CharSequence>(logs);
            logs.clear();
        }

        for (CharSequence s : copy) {
            try {
                output.append(s);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            output.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        postFlush();
    }

    private void postFlush() {
        flusher.postDelayed(flushable, 1000);
    }

    private class Flush implements Runnable {
        @Override
        public void run() {
            flush();
        }
    }

    private static final String preamble =
            "# tracer: nop\\n\\\n" +
            "#\\n\\\n" +
            "# entries-in-buffer/entries-written: 546/546   #P:4\\n\\\n" +
            "#\\n\\\n" +
            "#                                           _-----=> irqs-off\\n\\\n" +
            "#                                          / _----=> need-resched\\n\\\n" +
            "#                                         | / _---=> hardirq/softirq\\n\\\n" +
            "#                                         || / _--=> preempt-depth\\n\\\n" +
            "#                                         ||| /     delay\\n\\\n" +
            "#                TASK-PID    TGID   CPU#  ||||    TIMESTAMP  FUNCTION\\n\\\n" +
            "#                   | |        |      |   ||||       |         |\\n\\\n";
}

/*
Example "atrace -z -a package.name" output, after running through systrace.py's mangling or whatever.
Alignment seems unimportant, it sometimes messes up, and I've fixed this one by hand.
Time is based on nanotime, displayer makes everything relative to the first timestamp recorded:

# tracer: nop\n\
#\n\
# entries-in-buffer/entries-written: 546/546   #P:4\n\
#\n\
#                                           _-----=> irqs-off\n\
#                                          / _----=> need-resched\n\
#                                         | / _---=> hardirq/softirq\n\
#                                         || / _--=> preempt-depth\n\
#                                         ||| /     delay\n\
#                TASK-PID    TGID   CPU#  ||||    TIMESTAMP  FUNCTION\n\
#                   | |        |      |   ||||       |         |\n\
com.example.yourappyo-22964 (22964) [003] ...1 454529.028683: tracing_mark_write: B|22964|Application onCreate\n\
com.example.yourappyo-22964 (22964) [002] ...1 454529.167218: tracing_mark_write: E\n\
com.example.yourappyo-22964 (22964) [003] ...1 454529.224728: tracing_mark_write: B|22964|Main Activity onCreate\n\
com.example.yourappyo-22964 (22964) [003] ...1 454529.296558: tracing_mark_write: E\n\
         AsyncTask #1-23008 (22964) [001] ...1 454529.362070: tracing_mark_write: B|22964|AsyncTask doInBackground\n\
         AsyncTask #1-23008 (22964) [001] ...1 454529.362166: tracing_mark_write: B|22964|Building whatsits\n\
         AsyncTask #1-23008 (22964) [001] ...1 454529.362443: tracing_mark_write: B|22964|Getting Kronked\n\
         AsyncTask #5-23013 (22964) [001] ...1 454529.432899: tracing_mark_write: B|22964|AsyncTask doInBackground\n\
         AsyncTask #5-23013 (22964) [001] ...1 454529.432946: tracing_mark_write: B|22964|Something else\n\
         AsyncTask #2-23010 (22964) [003] ...1 454529.433091: tracing_mark_write: B|22964|AsyncTask doInBackground\n\
         AsyncTask #2-23010 (22964) [003] ...1 454529.433117: tracing_mark_write: B|22964|Something else\n\
         AsyncTask #3-23011 (22964) [000] ...1 454529.436063: tracing_mark_write: B|22964|AsyncTask doInBackground\n\
         AsyncTask #3-23011 (22964) [000] ...1 454529.436104: tracing_mark_write: B|22964|Something else\n\
         AsyncTask #4-23012 (22964) [001] ...1 454529.438493: tracing_mark_write: B|22964|AsyncTask doInBackground\n\
         AsyncTask #4-23012 (22964) [003] ...1 454529.440331: tracing_mark_write: B|22964|Something else\n\
         AsyncTask #5-23013 (22964) [003] ...1 454529.441335: tracing_mark_write: E\n\
         AsyncTask #5-23013 (22964) [003] ...1 454529.441348: tracing_mark_write: E\n\
         AsyncTask #5-23013 (22964) [003] ...1 454529.441731: tracing_mark_write: B|22964|AsyncTask doInBackground\n\
         AsyncTask #5-23013 (22964) [003] ...1 454529.441757: tracing_mark_write: B|22964|Something else\n\
         AsyncTask #5-23013 (22964) [003] ...1 454529.441783: tracing_mark_write: E\n\
         AsyncTask #5-23013 (22964) [003] ...1 454529.441792: tracing_mark_write: E\n\
         AsyncTask #5-23013 (22964) [003] ...1 454529.441980: tracing_mark_write: B|22964|AsyncTask doInBackground\n\
         AsyncTask #5-23013 (22964) [003] ...1 454529.442005: tracing_mark_write: B|22964|Something else\n\
         AsyncTask #3-23011 (22964) [003] ...1 454529.445055: tracing_mark_write: E\n\
         AsyncTask #3-23011 (22964) [003] ...1 454529.445068: tracing_mark_write: E\n\
         AsyncTask #3-23011 (22964) [003] ...1 454529.445399: tracing_mark_write: B|22964|AsyncTask doInBackground\n\
         AsyncTask #3-23011 (22964) [003] ...1 454529.445424: tracing_mark_write: B|22964|Something else\n\
         AsyncTask #4-23012 (22964) [000] ...1 454529.451088: tracing_mark_write: E\n\
         AsyncTask #4-23012 (22964) [000] ...1 454529.451108: tracing_mark_write: E\n\
         AsyncTask #5-23013 (22964) [001] ...1 454529.451401: tracing_mark_write: E\n\
         AsyncTask #5-23013 (22964) [001] ...1 454529.451413: tracing_mark_write: E\n\
         AsyncTask #2-23010 (22964) [002] ...1 454529.454536: tracing_mark_write: E\n\
         AsyncTask #2-23010 (22964) [002] ...1 454529.454549: tracing_mark_write: E\n\
         AsyncTask #3-23011 (22964) [002] ...1 454529.461293: tracing_mark_write: E\n\
         AsyncTask #3-23011 (22964) [002] ...1 454529.461308: tracing_mark_write: E\n\
 */
