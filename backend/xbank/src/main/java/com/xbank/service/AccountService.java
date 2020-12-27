package com.xbank.service;

import com.xbank.config.Constants;
import com.xbank.domain.Account;
import com.xbank.domain.Notification;
import com.xbank.domain.Transaction;
import com.xbank.dto.AccountDTO;
import com.xbank.dto.AccountTranferDTO;
import com.xbank.dto.WithDrawDTO;
import com.xbank.event.TransactionEvent;
import com.xbank.repository.AccountRepository;
import com.xbank.repository.NotificationRepository;
import com.xbank.repository.TransactionRepository;
import com.xbank.rest.errors.AccountExitsException;
import com.xbank.rest.errors.TranferException;
import com.xbank.rest.errors.UserNotfoundException;
import com.xbank.rest.errors.WithdrawException;
import com.xbank.security.SecurityUtils;
import io.github.jhipster.web.util.HeaderUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;

/**
 * Service class for managing accounts.
 */
@Service
public class AccountService {

    private final Logger log = LoggerFactory.getLogger(AccountService.class);

    @Value("${clientApp.name}")
    private String applicationName;

    private final AccountRepository accountRepository;

    private final TransactionRepository transactionRepository;

    private final NotificationRepository notificationRepository;

    private final ApplicationEventPublisher publisher;

    public AccountService(AccountRepository accountRepository, TransactionRepository transactionRepository, NotificationRepository notificationRepository, ApplicationEventPublisher publisher) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.notificationRepository = notificationRepository;
        this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public Mono<Long> countAccountsByUser(String username) {
        return accountRepository.countByUser(username);
    }


    @Transactional(readOnly = true)
    public Mono<Long> countAccounts() {
        return accountRepository.countAll();
    }

    public Flux<Account> getAccountByUser(String owner) {
        return accountRepository.findByOwner(owner);
    }

    public Mono<Account> getAccountDetail(String username, String account) {
        return accountRepository.getAccountDetail(username, account);
    }


    public Flux<Account> getAccounts(Pageable pageable) {
        return accountRepository.findByOwnerAsPage(pageable);
    }

    @Transactional
    public Mono<ResponseEntity<Account>> createAccount(AccountDTO accountDTO) {
        Account account = new Account();
        account.setAccount(accountDTO.getAccount());
        account.setAction(accountDTO.getAction());
        account.setCurrency(accountDTO.getCurrency());
        account.setBalance(accountDTO.getBalance());

        log.info("Insert data account.");
        return SecurityUtils.getCurrentUserLogin()
                .switchIfEmpty(Mono.just(Constants.SYSTEM_ACCOUNT))
                .flatMap(login -> {
                    account.setOwner(login);
                    account.setLastModifiedBy(login);
                    if (account.getCreatedBy() == null) {
                        account.setCreatedBy(login);
                    }
                    return accountRepository.findOneByAccount(accountDTO.getAccount())
                            .hasElement()
                            .flatMap(loginExists -> {
                                if (Boolean.TRUE.equals(loginExists)) {
                                    return Mono.error(new AccountExitsException());
                                }
                                return accountRepository.save(account);
                            }).map(acc -> {
                                try {
                                    return ResponseEntity.created(new URI("/api/accounts/" + acc.getId()))
                                            .headers(HeaderUtil.createAlert(applicationName, "accountManagement.created", acc.getAccount()))
                                            .body(acc);
                                } catch (URISyntaxException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                });
    }

    @Transactional
    public Mono<ResponseEntity<Account>> transfer(AccountTranferDTO data) {
        return SecurityUtils.getCurrentUserLogin()
//                .switchIfEmpty(Mono.just(Constants.SYSTEM_ACCOUNT))
                .flatMap(login -> {
                    if(StringUtils.isBlank(login)) {
                        return Mono.error(new UserNotfoundException());
                    }
                    return accountRepository.findOneByAccount(data.getAccount())
                            .flatMap(account -> {
                                if (account.getBalance().subtract(data.getBalance()).compareTo(BigDecimal.ZERO) < NumberUtils.INTEGER_ZERO) {
                                    throw new TranferException();
                                }
                                Transaction transaction = new Transaction();
                                transaction.setOwner(login);
                                transaction.setAction(1);
                                transaction.setAccount(data.getAccount());
                                transaction.setToAccount(data.getAccount());
                                transaction.setAmount(data.getBalance());
                                transaction.setCurrency("VND");
                                transaction.setTransactAt(LocalDateTime.now());
                                transaction.setResult(1);
                                transaction.setError("No error");
                                if (transaction.getCreatedBy() == null) {
                                    transaction.setCreatedBy(login);
                                }
                                transaction.setLastModifiedBy(login);
                                return transactionRepository.save(transaction).flatMap(t -> {
                                    account.setBalance(account.getBalance().subtract(data.getBalance()));
                                    return accountRepository.save(account).flatMap(acc -> accountRepository.findOneByAccount(data.getToAccount()).flatMap(acc1 -> {
                                        acc1.setBalance(acc1.getBalance().add(data.getBalance()));
                                        return accountRepository.save(acc1);
                                    }));
                                }).doOnSuccess(item -> publishTransactionEvent(TransactionEvent.ITEM_CREATED, transaction));
                            }).map(acc -> {
                                try {
                                    return ResponseEntity.created(new URI("/api/accounts/tranfer" + acc.getId()))
                                            .headers(HeaderUtil.createAlert(applicationName, "accountManagement.tranfered", acc.getAccount()))
                                            .body(acc);
                                } catch (URISyntaxException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                });
    }

    @Transactional
    public Mono<ResponseEntity<Account>> withDraw(WithDrawDTO data) {
        return SecurityUtils.getCurrentUserLogin()
                .switchIfEmpty(Mono.just(Constants.SYSTEM_ACCOUNT))
                .flatMap(login -> {
                    if(StringUtils.isBlank(login)) {
                        return Mono.error(new UserNotfoundException());
                    }
                    return accountRepository.findOneByAccount(data.getAccount())
                            .flatMap(account -> {
                                if (account.getBalance().subtract(data.getBalance()).compareTo(BigDecimal.ZERO) < NumberUtils.INTEGER_ZERO) {
                                    throw new WithdrawException();
                                }
                                Transaction transaction = new Transaction();
                                transaction.setOwner(login);
                                transaction.setAction(2);
                                transaction.setAccount(data.getAccount());
                                transaction.setToAccount(data.getAccount());
                                transaction.setAmount(data.getBalance());
                                transaction.setCurrency("VND");
                                transaction.setTransactAt(LocalDateTime.now());
                                transaction.setResult(1);
                                transaction.setError("No error");
                                if (transaction.getCreatedBy() == null) {
                                    transaction.setCreatedBy(login);
                                }
                                transaction.setLastModifiedBy(login);
                                return transactionRepository.save(transaction).flatMap(t -> {
                                    account.setBalance(account.getBalance().subtract(data.getBalance()));
                                    return accountRepository.save(account);
                                }).doOnSuccess(item -> publishTransactionEvent(TransactionEvent.ITEM_CREATED, transaction));
                            }).map(acc -> {
                                try {
                                    return ResponseEntity.created(new URI("/api/accounts/withdraw" + acc.getId()))
                                            .headers(HeaderUtil.createAlert(applicationName, "accountManagement.withdraw", acc.getAccount()))
                                            .body(acc);
                                } catch (URISyntaxException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                });
    }

    @Transactional
    public Mono<ResponseEntity<Account>> deposit(WithDrawDTO data) {
        return SecurityUtils.getCurrentUserLogin()
                .switchIfEmpty(Mono.just(Constants.SYSTEM_ACCOUNT))
                .flatMap(login -> {
                            if(StringUtils.isBlank(login)) {
                                return Mono.error(new UserNotfoundException());
                            }
                            return accountRepository.findOneByAccount(data.getAccount())
                                    .flatMap(account -> {
                                        Transaction transaction = new Transaction();
                                        transaction.setOwner(login);
                                        transaction.setAction(3);
                                        transaction.setAccount(data.getAccount());
                                        transaction.setToAccount(data.getAccount());
                                        transaction.setAmount(data.getBalance());
                                        transaction.setCurrency("VND");
                                        transaction.setTransactAt(LocalDateTime.now());
                                        transaction.setResult(1);
                                        transaction.setError("No error");
                                        if (transaction.getCreatedBy() == null) {
                                            transaction.setCreatedBy(login);
                                        }
                                        transaction.setLastModifiedBy(login);
                                        return transactionRepository.save(transaction).flatMap(t -> {
                                            account.setBalance(account.getBalance().add(data.getBalance()));
                                            return accountRepository.save(account);
                                        }).doOnSuccess(item -> publishTransactionEvent(TransactionEvent.ITEM_CREATED, transaction));
                                    }).map(acc -> {
                                        try {
                                            return ResponseEntity.created(new URI("/api/accounts/deposit" + acc.getId()))
                                                    .headers(HeaderUtil.createAlert(applicationName, "accountManagement.deposit", acc.getAccount()))
                                                    .body(acc);
                                        } catch (URISyntaxException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                        });
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
