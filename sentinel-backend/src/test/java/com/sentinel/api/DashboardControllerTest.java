package com.sentinel.api;

import com.sentinel.model.entity.ServiceHealthEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.model.enums.ServiceStatus;
import com.sentinel.repository.IncidentRepository;
import com.sentinel.repository.ServiceHealthRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ServiceHealthRepository serviceHealthRepository;

    @MockBean
    private IncidentRepository incidentRepository;

    @Test
    void getSummary_returnsServiceHealthList() throws Exception {
        ServiceHealthEntity svc = new ServiceHealthEntity();
        svc.setServiceName("payment-service");
        svc.setStatus(ServiceStatus.GREEN);
        svc.setErrorRate(0.1);
        svc.setLatencyP95(150.0);
        svc.setLastUpdated(Instant.now());

        when(serviceHealthRepository.findAll()).thenReturn(List.of(svc));

        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceName").value("payment-service"))
                .andExpect(jsonPath("$[0].status").value("GREEN"));
    }

    @Test
    void getActiveIncidents_returnsOpenIncidents() throws Exception {
        when(incidentRepository.findByStatusOrderByDetectedAtDesc(IncidentStatus.OPEN))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/dashboard/incidents/active"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void getStats_returnsCorrectCounts() throws Exception {
        when(incidentRepository.findByStatusOrderByDetectedAtDesc(IncidentStatus.OPEN))
                .thenReturn(List.of());
        when(incidentRepository.findRecentIncidents(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(serviceHealthRepository.count()).thenReturn(5L);

        mockMvc.perform(get("/api/dashboard/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openIncidents").value(0))
                .andExpect(jsonPath("$.serviceCount").value(5));
    }
}
