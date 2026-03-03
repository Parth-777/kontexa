package com.example.BACKEND.service;

import com.example.BACKEND.entity.FormSubmission;
import com.example.BACKEND.repository.FormSubmissionRepository;
import org.springframework.stereotype.Service;

@Service
public class FormSubmissionService {
    private final FormSubmissionRepository repository;

    public FormSubmissionService(FormSubmissionRepository repository) {
        this.repository = repository;
    }

    public FormSubmission save(FormSubmission formSubmission) {
        return repository.save(formSubmission);
    }


}
