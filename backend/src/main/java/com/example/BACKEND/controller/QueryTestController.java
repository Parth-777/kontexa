package com.example.BACKEND.controller;

import com.example.BACKEND.analytics.query.CanonicalQuery;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/query")
public class QueryTestController {

    @PostMapping("/parse")
    public CanonicalQuery parse(@RequestBody CanonicalQuery query) {
        return query;
    }



}

