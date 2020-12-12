package com.xbank.service;

import com.xbank.config.Constants;
import com.xbank.domain.Notification;
import com.xbank.domain.Transaction;
import com.xbank.dto.TransactionDTO;
import com.xbank.event.TransactionEvent;
import com.xbank.repository.NotificationRepository;
import com.xbank.repository.TransactionRepository;
import com.xbank.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Service class for managing Transactions.
 */
@Service
public class TransactionService {

    private final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final NotificationRepository notificationRepository;

    private final ApplicationEventPublisher publisher;

    public TransactionService(TransactionRepository transactionRepository, NotificationRepository notificationRepository, ApplicationEventPublisher publisher) {
        this.transactionRepository = transactionRepository;
        this.notificationRepository = notificationRepository;
        this.publisher = publisher;
    }

    @Transactional
    public Mono<Transaction> createTransaction(TransactionDTO transactionDTO) {
        log.info("Insert data Transaction.");
        return SecurityUtils.getCurrentUserLogin()
                .switchIfEmpty(Mono.just(Constants.SYSTEM_ACCOUNT))
                .flatMap(login -> {

                    Transaction transaction = new Transaction();
                    transaction.setOwner(transactionDTO.getAccount());
                    transaction.setAction(transactionDTO.getAction());
                    transaction.setAccount(transactionDTO.getAccount());
                    transaction.setToAccount(transactionDTO.getToAccount());
                    transaction.setAmount(transactionDTO.getAmount());
                    transaction.setCurrency(transactionDTO.getCurrency());
                    transaction.setTransactAt(LocalDateTime.now());
                    transaction.setResult(1);
                    transaction.setError("No error");
                    if (transaction.getCreatedBy() == null) {
                        transaction.setCreatedBy(login);
                    }
                    transaction.setLastModifiedBy(login);
                    return transactionRepository.save(transaction)
                            .doOnSuccess(item -> publishTransactionEvent(TransactionEvent.ITEM_CREATED, item));
                });
    }

    @Transactional(readOnly = true)
    public Mono<Long> countTransactions() {
        return transactionRepository.countAll();
    }

    @Transactional(readOnly = true)
    public Flux<Transaction> getAllTransactions(Pageable pageable) {
        return transactionRepository.findAllAsPage(pageable);
    }

    private final void publishTransactionEvent(String eventType, Transaction transaction) {
        this.publisher.publishEvent(new TransactionEvent(eventType, transaction));
        Notification notification = new Notification();
        notification.setAccount(transaction.getToAccount());
        notification.setCreatedDate(LocalDateTime.now());
        notification.setLastModifiedDate(LocalDateTime.now());
        if (transaction.getAction() == 1) {
            // Tranfer action
            notification.setTitle(transaction.getAccount() + " has transferred to you " + transaction.getAmount() + " at " + transaction.getTransactAt());
        } else if (transaction.getAction() == 2) {
            // withdraw action
            notification.setTitle("Withdraw " + transaction.getAmount() + " at " + transaction.getTransactAt());
        } else if (transaction.getAction() == 3) {
            // deposit action
            notification.setTitle("Deposit " + transaction.getAmount() + " at " + transaction.getTransactAt());
        }
        notificationRepository.save(notification).subscribe(result -> log.info("Entity has been saved: {}", result));
    }

}