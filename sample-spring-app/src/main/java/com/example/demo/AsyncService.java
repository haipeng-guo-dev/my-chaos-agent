package com.example.demo;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class AsyncService {

    @Async
    public CompletableFuture<String> virtualTask(int id) {
        return CompletableFuture.completedFuture("Task " + id + " on " + Thread.currentThread());
    }
}