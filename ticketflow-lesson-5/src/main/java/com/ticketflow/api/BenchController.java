package com.ticketflow.api;

import com.ticketflow.bench.BenchService;
import com.ticketflow.bench.RampPlan;
import com.ticketflow.bench.RunReport;
import com.ticketflow.config.TicketFlowProperties;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Starts ramps and serves their results to the dashboard at {@code /}. */
@RestController
@RequestMapping("/api/bench")
public class BenchController {

    private final BenchService bench;
    private final TicketFlowProperties properties;

    public BenchController(BenchService bench, TicketFlowProperties properties) {
        this.bench = bench;
        this.properties = properties;
    }

    @GetMapping("/presets")
    public List<TicketFlowProperties.Preset> presets() {
        return properties.bench().presets();
    }

    @PostMapping("/runs")
    public ResponseEntity<RunReport> start(@RequestBody RampPlan plan) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(bench.start(plan));
    }

    @GetMapping("/runs")
    public List<RunReport> runs() {
        return bench.runs();
    }

    @DeleteMapping("/runs")
    public ResponseEntity<Void> clear() {
        bench.clear();
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/summary", produces = MediaType.TEXT_PLAIN_VALUE)
    public String summary() {
        return bench.summary();
    }
}
