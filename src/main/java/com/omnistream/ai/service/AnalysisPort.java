package com.omnistream.ai.service;

import com.omnistream.ai.dto.ai.StructuredAnalysisResult;

public interface AnalysisPort {
    StructuredAnalysisResult parse(EventMessage event);
}
