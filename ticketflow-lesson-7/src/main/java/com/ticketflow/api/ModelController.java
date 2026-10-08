package com.ticketflow.api;

import com.ticketflow.model.RushModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The paper estimate from lesson 1, kept so it can be compared with what lesson 5 measures. */
@RestController
@RequestMapping("/api/model")
public class ModelController {

    @GetMapping("/rush")
    public RushModel.Result rush(@RequestParam(defaultValue = "10000") int buyers,
                                 @RequestParam(defaultValue = "500") int seats,
                                 @RequestParam(defaultValue = "1") double windowSeconds,
                                 @RequestParam(defaultValue = "8") double cpuMillis,
                                 @RequestParam(defaultValue = "1") int cores) {
        return RushModel.estimate(buyers, seats, windowSeconds, cpuMillis, cores);
    }
}
