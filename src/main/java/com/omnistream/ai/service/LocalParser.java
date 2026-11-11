package com.omnistream.ai.service;

import com.omnistream.ai.dto.ai.StructuredAnalysisResult;
import com.omnistream.ai.dto.ai.StructuredAnalysisResult.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "omnistream.parser-mode", havingValue = "local")
public class LocalParser implements AnalysisPort {
    private static final Pattern FIELD = Pattern.compile("(?i)\\b(invoice|account|sensor|host|region|amount|temperature|device|service)\\s*[:=]\\s*([\\w.$/-]+)");
    private static final Pattern FINANCE = Pattern.compile("invoice|payment|transfer|refund|transaction");
    private static final Pattern SENSOR = Pattern.compile("sensor|temperature|telemetry|device");
    private static final Pattern SYSTEM = Pattern.compile("server|error|deploy|timeout|log|service");

    @Override
    public StructuredAnalysisResult parse(EventMessage event) {
        var payload = event.rawPayload().strip();
        var text = (event.eventType() + " " + payload).toLowerCase(Locale.ROOT);
        Category category = Category.UNKNOWN;
        if (FINANCE.matcher(text).find()) category = Category.FINANCIAL;
        else if (SENSOR.matcher(text).find()) category = Category.IOT_TELEMETRY;
        else if (SYSTEM.matcher(text).find()) category = Category.SYSTEM_LOG;
        Risk risk = Risk.LOW;
        if (text.contains("critical") || text.contains("breach")) risk = Risk.CRITICAL;
        else if (text.contains("failed") || text.contains("outage")) risk = Risk.HIGH;
        else if (text.contains("warning") || text.contains("overdue")) risk = Risk.MEDIUM;
        var score = switch (risk) {
            case CRITICAL -> "-0.9";
            case HIGH -> "-0.6";
            case MEDIUM -> "-0.2";
            case LOW -> "0.0";
        };
        var matcher = FIELD.matcher(payload);
        var entities = new ArrayList<Entity>();
        while (matcher.find() && entities.size() < 32) entities.add(new Entity(matcher.group(1), matcher.group(2)));
        String summary = payload.lines().findFirst().orElse(payload);
        if (summary.length() > 200) summary = summary.substring(0, 199) + "…";
        return new StructuredAnalysisResult(summary, category, new BigDecimal(score), risk, entities);
    }
}
