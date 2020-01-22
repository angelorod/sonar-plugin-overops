package com.overops.plugins.sonar.rules.checks;

import com.overops.plugins.sonar.measures.OverOpsEventsStatistic;
import com.overops.plugins.sonar.measures.OverOpsEventsStatistic.StatEvent;
import com.overops.plugins.sonar.measures.OverOpsMetrics;
import com.takipi.api.client.functions.output.RegressionRow;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.java.api.JavaFileScanner;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.overops.plugins.sonar.util.CommonMethods.getJavaStyleFilePath;
import static com.overops.plugins.sonar.OverOpsConfigurationDataManager.overOpsEventsStatistic;
import static com.overops.plugins.sonar.measures.OverOpsQualityGateStat.INCREASING_QG_MARKER;

public abstract class OverOpsBaseExceptionCheck extends BaseTreeVisitor implements JavaFileScanner {
    private static final Logger log = Loggers.get(OverOpsBaseExceptionCheck.class);
    private JavaFileScannerContext context;
    private File file;
    protected OverOpsMetrics.OverOpsMetric metric;
    public static Map<String, Long> fileToLinesCount = new HashMap<>();

    @Override
    public void scanFile(final @Nonnull JavaFileScannerContext ctx) {
        if (overOpsEventsStatistic == null) {
            return;
        }

        context = ctx;
        file = context.getFile();
        String javaStyleFilePath = getJavaStyleFilePath(file.getAbsolutePath());
        int endingIndex = javaStyleFilePath.length() - ".java".length();
        List<OverOpsEventsStatistic.ClassStat> fileStatistics = overOpsEventsStatistic.getStatistic()
                .stream()
                .filter(classStat -> {
                    return javaStyleFilePath.indexOf(classStat.fileName) == endingIndex - classStat.fileName.length();})
                .filter(classStat -> classStat.qualityGateToEventStat.keySet().contains(metric.qualityGateKey))
                .collect(Collectors.toList());

        for (OverOpsEventsStatistic.ClassStat classStat : fileStatistics) {
            OverOpsEventsStatistic.EventInClassStat eventInClassStat = classStat.qualityGateToEventStat.get(metric.qualityGateKey);
            for (int lineNumber : eventInClassStat.lineToLineStat.keySet()) {
                reportIssue(eventInClassStat.lineToLineStat.get(lineNumber));
            }
        }

        scan(ctx.getTree());
    }

    public void reportIssue(OverOpsEventsStatistic.LineStat lineStat) {
        StatEvent statEvent = lineStat.event;
        int method_position = statEvent.eventMethodPosition + 1;
        long fileCount = getFileCount();
        boolean isMethodPresent = fileCount >= method_position;
        method_position = isMethodPresent ? method_position : 1;
        log.info("                  Reporting [" + lineStat.event.qualityGatesKey + "]  oMP [" + statEvent.eventMethodPosition + "]  on " + lineStat.event.eventClassIdentifier);
        String issueTitle = getIssueTitle(lineStat);

        context.addIssue(method_position , this, issueTitle);
    }

    private String getIssueTitle(OverOpsEventsStatistic.LineStat lineStat) {
        StatEvent statEvent = lineStat.event;
        String qualityGateKey = String.join(" and ", metric.qualityGate);
        StringBuilder stringBuilder = new StringBuilder();
        String messagePrefix = OverOpsMetrics.MESSAGE_PATTERN_PREFIX;
        if (lineStat.event.qualityGates.contains(INCREASING_QG_MARKER)) {
            RegressionRow regressionRow = overOpsEventsStatistic.getOverOpsQualityGateStat().increasingEventsIds.get(statEvent.eventId);
            int percents = (int)(regressionRow.reg_delta * 100);
            messagePrefix = "has been occurring " + String.valueOf(percents) + "% more [ID-";
        }
        return stringBuilder.append(qualityGateKey)
                .append(" ")
                .append(statEvent.eventSummary)
                .append(messagePrefix)
                .append(statEvent.eventId)
                .append(OverOpsMetrics.MESSAGE_PATTERN_SUFFIX)
                .toString();
    }

    private long getFileCount() {
        try {
            String path = file.getPath();
            Long fileLines = fileToLinesCount.get(path);
            if (fileLines == null) {
                long count = Files.lines(file.toPath()).count();
                fileToLinesCount.put(path, count);
                return count;
            } else {
                return fileLines;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return 1;
    }
}