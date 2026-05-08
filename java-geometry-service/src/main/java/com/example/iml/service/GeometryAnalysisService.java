package com.example.iml.service;

import com.example.iml.dto.InspectionRequest;
import com.example.iml.dto.InspectionResponse;

public interface GeometryAnalysisService {
    InspectionResponse inspect(InspectionRequest request);
}
