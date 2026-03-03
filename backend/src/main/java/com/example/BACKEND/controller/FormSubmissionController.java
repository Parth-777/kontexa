package com.example.BACKEND.controller;

import com.example.BACKEND.entity.FormSubmission;
import com.example.BACKEND.service.FormSubmissionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forms")
@CrossOrigin(origins = "http://localhost:3000")
public class FormSubmissionController {

    private final FormSubmissionService service;

    public FormSubmissionController(FormSubmissionService service) {
        this.service = service;
    }

    @PostMapping("/submit")
    public FormSubmission submitForm(@RequestBody FormSubmission form) {
        return service.save(form);
    }
}
