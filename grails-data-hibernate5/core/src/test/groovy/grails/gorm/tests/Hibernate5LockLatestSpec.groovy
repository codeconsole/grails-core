/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package grails.gorm.tests

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import jakarta.persistence.TransactionRequiredException

import org.hibernate.FlushMode
import org.hibernate.Hibernate
import org.hibernate.LockMode
import org.hibernate.Session

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.transaction.support.TransactionSynchronizationManager

import grails.gorm.annotation.Entity
import grails.gorm.dirty.checking.DirtyCheck

class Hibernate5LockLatestSpec extends HibernateGormDatastoreSpec {

    void setupSpec() {
        manager.registerDomainClasses(Hibernate5LockLatestBook, Hibernate5LockLatestRoutedBook,
                Hibernate5LockLatestNonversionedBook, Hibernate5LockLatestEmbeddedBook)
        // Let the template preserve the session flush mode instead of downgrading AUTO to COMMIT.
        manager.grailsConfig['hibernate.flush.mode'] = 'AUTO'
        // ConfigObject needs the parent map for named connection discovery.
        manager.grailsConfig.dataSources.secondary = [
            url: 'jdbc:h2:mem:hibernate5LockLatestSecondary;LOCK_TIMEOUT=10000'
        ]
    }

    void 'ordinary lock preserves pending changes and takes a write lock'() {
        given:
        def book = new Hibernate5LockLatestBook(title: 'original').save(flush: true, failOnError: true)
        Hibernate5LockLatestBook.withSession { it.clear() }
        book = Hibernate5LockLatestBook.get(book.id)
        book.title = 'pending'

        when:
        def result = book.lock()

        then:
        result.is(book)
        book.title == 'pending'
        book.version == 0
        Hibernate5LockLatestBook.withSession { Session session ->
            session.getCurrentLockMode(book) == LockMode.PESSIMISTIC_WRITE
        }
    }

    void 'lockLatest discards pending changes under #flushMode and takes a write lock'() {
        given:
        def book = new Hibernate5LockLatestBook(title: 'original').save(flush: true, failOnError: true)
        Hibernate5LockLatestBook.withSession { it.clear() }
        book = Hibernate5LockLatestBook.get(book.id)
        FlushMode previousFlushMode = Hibernate5LockLatestBook.withSession { Session session ->
            FlushMode previous = session.hibernateFlushMode
            session.setHibernateFlushMode(flushMode)
            previous
        }
        book.title = 'pending'
        assert book.isDirty('title')

        when:
        def result = book.lockLatest()

        then:
        result.is(book)
        book.title == 'original'
        book.version == 0
        !book.isDirty('title')
        !book.isDirty()
        book.getDirtyPropertyNames().isEmpty()
        book.listDirtyPropertyNames().isEmpty()
        Hibernate5LockLatestBook.withSession { Session session ->
            session.getCurrentLockMode(book) == LockMode.PESSIMISTIC_WRITE && session.hibernateFlushMode == flushMode
        }

        when: 'the refreshed entity is flushed without further edits'
        Hibernate5LockLatestBook.withSession { it.flush() }

        then:
        book.version == 0

        when:
        Hibernate5LockLatestBook.withSession { it.clear() }

        then:
        Hibernate5LockLatestBook.get(book.id).title == 'original'
        Hibernate5LockLatestBook.get(book.id).version == 0

        cleanup:
        if (previousFlushMode != null) {
            Hibernate5LockLatestBook.withSession { Session session ->
                session.setHibernateFlushMode(previousFlushMode)
            }
        }

        where:
        flushMode << [FlushMode.AUTO, FlushMode.COMMIT]
    }

    void 'lockLatest discards scalar and embedded edits without a version bump under #flushMode'() {
        given:
        def book = new Hibernate5LockLatestEmbeddedBook(
                title: 'original',
                details: new Hibernate5LockLatestDetails(summary: 'original summary', language: 'English')
        ).save(flush: true, failOnError: true)
        Hibernate5LockLatestEmbeddedBook.withSession { it.clear() }
        book = Hibernate5LockLatestEmbeddedBook.get(book.id)
        FlushMode previousFlushMode = Hibernate5LockLatestEmbeddedBook.withSession { Session session ->
            FlushMode previous = session.hibernateFlushMode
            session.setHibernateFlushMode(flushMode)
            previous
        }
        book.title = 'pending title'
        book.details.summary = 'pending summary'
        assert book.isDirty('title')
        assert book.details.hasChanged('summary')

        when:
        def result = book.lockLatest()

        then:
        result.is(book)
        book.title == 'original'
        book.details.summary == 'original summary'
        book.details.language == 'English'
        !book.isDirty('title')
        !book.isDirty()
        book.getDirtyPropertyNames().isEmpty()
        book.listDirtyPropertyNames().isEmpty()
        !book.details.hasChanged('summary')
        !book.details.hasChanged()
        book.details.listDirtyPropertyNames().isEmpty()
        Hibernate5LockLatestEmbeddedBook.withSession { Session session ->
            session.getCurrentLockMode(book) == LockMode.PESSIMISTIC_WRITE && session.hibernateFlushMode == flushMode
        }

        when: 'the discarded edits must not schedule an update at flush'
        Hibernate5LockLatestEmbeddedBook.withSession { it.flush() }

        then:
        book.version == 0

        when:
        Hibernate5LockLatestEmbeddedBook.withSession { it.clear() }
        book = Hibernate5LockLatestEmbeddedBook.get(book.id)

        then:
        book.version == 0
        book.title == 'original'
        book.details.summary == 'original summary'
        book.details.language == 'English'

        when: 'subsequent scalar and embedded edits are still tracked'
        book.title = 'saved title'
        book.details.summary = 'saved summary'
        book.save(flush: true, failOnError: true)
        Hibernate5LockLatestEmbeddedBook.withSession { it.clear() }
        book = Hibernate5LockLatestEmbeddedBook.get(book.id)

        then:
        book.version == 1
        book.title == 'saved title'
        book.details.summary == 'saved summary'
        book.details.language == 'English'

        cleanup:
        if (previousFlushMode != null) {
            Hibernate5LockLatestEmbeddedBook.withSession { Session session ->
                session.setHibernateFlushMode(previousFlushMode)
            }
        }

        where:
        flushMode << [FlushMode.AUTO, FlushMode.COMMIT]
    }

    void 'ordinary lock rejects a stale committed version'() {
        given:
        Long id = new Hibernate5LockLatestBook(title: 'original').save(flush: true, failOnError: true).id
        manager.transactionManager.commit(manager.transactionStatus)
        manager.transactionStatus = null
        def executor = Executors.newSingleThreadExecutor()

        when:
        Hibernate5LockLatestBook.withNewSession {
            Hibernate5LockLatestBook.withTransaction {
                def book = Hibernate5LockLatestBook.get(id)
                assert book.version == 0

                executor.submit({
                    Hibernate5LockLatestBook.withNewSession {
                        Hibernate5LockLatestBook.withTransaction {
                            def competingBook = Hibernate5LockLatestBook.get(id)
                            competingBook.title = 'competing commit'
                            competingBook.save(flush: true, failOnError: true)
                            assert competingBook.version == 1
                        }
                    }
                } as Callable).get(10, TimeUnit.SECONDS)

                assert book.title == 'original'
                assert book.version == 0
                book.lock()
            }
        }

        then:
        thrown(OptimisticLockingFailureException)

        cleanup:
        executor?.shutdownNow()
        assert executor == null || executor.awaitTermination(15, TimeUnit.SECONDS)
    }

    void 'lockLatest reloads a committed stale version and allows a subsequent save'() {
        given:
        Long id = new Hibernate5LockLatestBook(title: 'original').save(flush: true, failOnError: true).id
        manager.transactionManager.commit(manager.transactionStatus)
        manager.transactionStatus = null
        def executor = Executors.newSingleThreadExecutor()

        when:
        Hibernate5LockLatestBook.withNewSession { Session session ->
            Hibernate5LockLatestBook.withTransaction {
                def book = Hibernate5LockLatestBook.get(id)
                assert book.version == 0

                executor.submit({
                    Hibernate5LockLatestBook.withNewSession {
                        Hibernate5LockLatestBook.withTransaction {
                            def competingBook = Hibernate5LockLatestBook.get(id)
                            competingBook.title = 'competing commit'
                            competingBook.save(flush: true, failOnError: true)
                            assert competingBook.version == 1
                        }
                    }
                } as Callable).get(10, TimeUnit.SECONDS)

                assert book.title == 'original'
                assert book.version == 0
                book.title = 'stale pending edit'
                assert book.isDirty('title')
                assert book.lockLatest().is(book)
                assert book.title == 'competing commit'
                assert book.version == 1
                assert !book.isDirty('title')
                assert !book.isDirty()
                assert book.getDirtyPropertyNames().isEmpty()
                assert book.listDirtyPropertyNames().isEmpty()
                assert session.getCurrentLockMode(book) == LockMode.PESSIMISTIC_WRITE

                book.title = 'saved after refresh'
                assert book.save(flush: true, failOnError: true).is(book)
                assert book.version == 2
            }
        }

        then:
        Hibernate5LockLatestBook.withNewSession {
            def book = Hibernate5LockLatestBook.get(id)
            assert book.title == 'saved after refresh'
            assert book.version == 2
            true
        }

        cleanup:
        executor?.shutdownNow()
        assert executor == null || executor.awaitTermination(15, TimeUnit.SECONDS)
    }

    void 'public operations lockLatest locks and refreshes a proxy (initialized: #initialized)'() {
        given:
        Long id = new Hibernate5LockLatestBook(title: 'original').save(flush: true, failOnError: true).id
        manager.transactionManager.commit(manager.transactionStatus)
        manager.transactionStatus = null
        def allOperations = Hibernate5LockLatestBook.'default'
        def executor = Executors.newSingleThreadExecutor()

        expect:
        allOperations.withNewSession { Session session ->
            allOperations.withTransaction {
                def proxy = allOperations.load(id)
                assert !Hibernate.isInitialized(proxy)
                if (initialized) {
                    proxy.title = 'pending'
                }

                executor.submit({
                    allOperations.withNewSession {
                        allOperations.withTransaction {
                            def competingBook = allOperations.get(id)
                            competingBook.title = 'competing commit'
                            competingBook.save(flush: true, failOnError: true)
                            assert competingBook.version == 1
                        }
                    }
                } as Callable).get(10, TimeUnit.SECONDS)

                assert Hibernate.isInitialized(proxy) == initialized
                def result = allOperations.lockLatest(proxy)
                assert result.is(proxy)
                assert Hibernate.isInitialized(proxy)
                assert session.getCurrentLockMode(proxy) == LockMode.PESSIMISTIC_WRITE
                assert proxy.title == 'competing commit'
                assert proxy.version == 1

                session.flush()
                assert proxy.version == 1
                proxy.title = 'saved after proxy refresh'
                allOperations.save(proxy, [flush: true, failOnError: true])
                assert proxy.version == 2
                true
            }
        }
        allOperations.withNewSession {
            def book = allOperations.get(id)
            assert book.title == 'saved after proxy refresh'
            assert book.version == 2
            true
        }

        cleanup:
        executor?.shutdownNow()
        assert executor == null || executor.awaitTermination(15, TimeUnit.SECONDS)

        where:
        initialized << [false, true]
    }

    void 'public operations lockLatest rejects a missing transaction before initializing a proxy'() {
        given:
        Long id = new Hibernate5LockLatestBook(title: 'original').save(flush: true, failOnError: true).id
        manager.transactionManager.commit(manager.transactionStatus)
        manager.transactionStatus = null
        def allOperations = Hibernate5LockLatestBook.'default'
        Hibernate5LockLatestBook proxy

        when:
        allOperations.withNewSession { Session session ->
            assert !session.getTransaction().isActive()
            proxy = allOperations.load(id)
            assert !Hibernate.isInitialized(proxy)
            allOperations.lockLatest(proxy)
        }

        then:
        thrown(TransactionRequiredException)
        !Hibernate.isInitialized(proxy)
    }

    void 'lockLatest discards changes and permits saving a nonversioned entity'() {
        given:
        def book = new Hibernate5LockLatestNonversionedBook(title: 'original').save(flush: true, failOnError: true)
        Hibernate5LockLatestNonversionedBook.withSession { it.clear() }
        book = Hibernate5LockLatestNonversionedBook.get(book.id)
        book.title = 'pending'
        assert book.isDirty('title')

        when:
        def result = book.lockLatest()

        then:
        result.is(book)
        book.title == 'original'
        !book.isDirty('title')
        !book.isDirty()
        book.getDirtyPropertyNames().isEmpty()
        book.listDirtyPropertyNames().isEmpty()
        Hibernate5LockLatestNonversionedBook.withSession { Session session ->
            session.getCurrentLockMode(book) == LockMode.PESSIMISTIC_WRITE
        }

        when:
        book.title = 'saved after refresh'
        book.save(flush: true, failOnError: true)
        Hibernate5LockLatestNonversionedBook.withSession { it.clear() }

        then:
        Hibernate5LockLatestNonversionedBook.get(book.id).title == 'saved after refresh'
    }

    void 'lockLatest requires an active transaction even with an open bound session and managed entity'() {
        given:
        def book = new Hibernate5LockLatestBook(title: 'original').save(flush: true, failOnError: true)
        manager.transactionManager.commit(manager.transactionStatus)
        manager.transactionStatus = null
        assert !TransactionSynchronizationManager.isActualTransactionActive()

        when:
        Hibernate5LockLatestBook.withNewSession { Session session ->
            assert session.isOpen()
            assert TransactionSynchronizationManager.hasResource(manager.sessionFactory)
            assert !TransactionSynchronizationManager.isActualTransactionActive()
            assert !session.getTransaction().isActive()
            book = Hibernate5LockLatestBook.get(book.id)
            assert session.contains(book)
            book.title = 'pending'
            book.lockLatest()
        }

        then:
        def exception = thrown(TransactionRequiredException)
        exception.message == 'An active transaction is required.'
        book.title == 'pending'
        book.version == 0
    }

    void 'lockLatest uses the named connection rather than the default database'() {
        given:
        def defaultBook = new Hibernate5LockLatestRoutedBook(title: 'default').save(flush: true, failOnError: true)
        Long defaultId = defaultBook.id
        Long defaultVersion = defaultBook.version

        expect:
        Hibernate5LockLatestRoutedBook.secondary.withTransaction {
            Hibernate5LockLatestRoutedBook.secondary.withSession { Session session ->
                assert session.getTransaction().isActive()
                def url = session.doReturningWork { connection -> connection.metaData.URL }
                assert url == 'jdbc:h2:mem:hibernate5LockLatestSecondary'
                def book = new Hibernate5LockLatestRoutedBook(title: 'secondary')
                book.secondary.save(flush: true, failOnError: true)
                Long secondaryId = book.id
                session.clear()
                book = Hibernate5LockLatestRoutedBook.secondary.get(secondaryId)
                assert session.contains(book)
                book.title = 'pending'

                assert book.secondary.lockLatest().is(book)
                assert book.title == 'secondary'
                assert book.version == 0
                assert session.getCurrentLockMode(book) == LockMode.PESSIMISTIC_WRITE

                session.flush()
                session.clear()
                def reloadedSecondary = Hibernate5LockLatestRoutedBook.secondary.get(secondaryId)
                assert reloadedSecondary.title == 'secondary'
                assert reloadedSecondary.version == 0
                true
            }
        }
        defaultBook.title == 'default'
        Hibernate5LockLatestRoutedBook.withSession { Session session ->
            session.flush()
            session.clear()
            def reloadedDefault = Hibernate5LockLatestRoutedBook.get(defaultId)
            assert reloadedDefault.title == 'default'
            assert reloadedDefault.version == defaultVersion
            assert Hibernate5LockLatestRoutedBook.countByTitle('secondary') == 0
            assert Hibernate5LockLatestRoutedBook.countByTitle('pending') == 0
            true
        }
    }

    void 'lockLatest requires a transaction on the named connection even when the default transaction is active'() {
        given:
        Long id = Hibernate5LockLatestRoutedBook.secondary.withNewSession {
            Hibernate5LockLatestRoutedBook.secondary.withTransaction {
                new Hibernate5LockLatestRoutedBook(title: 'secondary').secondary.save(flush: true, failOnError: true).id
            }
        }
        Hibernate5LockLatestRoutedBook book

        when:
        Hibernate5LockLatestRoutedBook.secondary.withNewSession { Session session ->
            book = Hibernate5LockLatestRoutedBook.secondary.get(id)
            assert session.isOpen()
            assert session.contains(book)
            assert !session.getTransaction().isActive()
            Hibernate5LockLatestRoutedBook.withSession { Session defaultSession ->
                assert defaultSession.getTransaction().isActive()
            }
            book.title = 'pending'
            book.secondary.lockLatest()
        }

        then:
        def exception = thrown(TransactionRequiredException)
        exception.message == 'An active transaction is required.'
        book.title == 'pending'
        book.version == 0
    }

    void 'lockLatest waits for a competing commit and holds the write lock until transaction completion'() {
        given:
        Long id = new Hibernate5LockLatestBook(title: 'original').save(flush: true, failOnError: true).id
        manager.transactionManager.commit(manager.transactionStatus)
        manager.transactionStatus = null
        def executor = Executors.newFixedThreadPool(3)
        def loaded = new CountDownLatch(1)
        def competingUpdate = new CountDownLatch(1)
        def allowCompetingCommit = new CountDownLatch(1)
        def refreshStarted = new CountDownLatch(1)
        def refreshed = new CountDownLatch(1)
        def allowRefreshCommit = new CountDownLatch(1)
        def contenderStarted = new CountDownLatch(1)
        def contenderLocked = new CountDownLatch(1)

        when:
        def refreshing = executor.submit({
            Hibernate5LockLatestBook.withNewSession { Session session ->
                Hibernate5LockLatestBook.withTransaction {
                    def book = Hibernate5LockLatestBook.get(id)
                    assert book.version == 0
                    book.title = 'pending edit'
                    loaded.countDown()
                    assert competingUpdate.await(10, TimeUnit.SECONDS)
                    refreshStarted.countDown()
                    assert book.lockLatest().is(book)
                    assert book.title == 'competing commit'
                    assert book.version == 1
                    assert session.getCurrentLockMode(book) == LockMode.PESSIMISTIC_WRITE
                    refreshed.countDown()
                    assert allowRefreshCommit.await(10, TimeUnit.SECONDS)
                }
            }
        } as Callable)
        def competing = executor.submit({
            Hibernate5LockLatestBook.withNewSession {
                Hibernate5LockLatestBook.withTransaction {
                    assert loaded.await(10, TimeUnit.SECONDS)
                    def book = Hibernate5LockLatestBook.get(id)
                    book.title = 'competing commit'
                    book.save(flush: true, failOnError: true)
                    competingUpdate.countDown()
                    assert allowCompetingCommit.await(10, TimeUnit.SECONDS)
                }
            }
        } as Callable)

        then: 'refresh cannot finish while the competing transaction owns the row lock'
        refreshStarted.await(10, TimeUnit.SECONDS)
        !refreshed.await(200, TimeUnit.MILLISECONDS)
        !refreshing.isDone()

        when:
        allowCompetingCommit.countDown()
        competing.get(10, TimeUnit.SECONDS)

        then: 'refresh observes the newly committed state while acquiring its write lock'
        refreshed.await(10, TimeUnit.SECONDS)

        when:
        def contender = executor.submit({
            Hibernate5LockLatestBook.withNewSession {
                Hibernate5LockLatestBook.withTransaction {
                    contenderStarted.countDown()
                    def book = Hibernate5LockLatestBook.lock(id)
                    contenderLocked.countDown()
                    assert book.title == 'competing commit'
                    book.title = 'last commit'
                    book.save(flush: true, failOnError: true)
                }
            }
        } as Callable)

        then: 'another writer cannot acquire the row until the refreshing transaction ends'
        contenderStarted.await(10, TimeUnit.SECONDS)
        !contenderLocked.await(200, TimeUnit.MILLISECONDS)
        !contender.isDone()

        when:
        allowRefreshCommit.countDown()
        refreshing.get(10, TimeUnit.SECONDS)
        contender.get(10, TimeUnit.SECONDS)

        then:
        contenderLocked.count == 0
        Hibernate5LockLatestBook.withNewSession {
            def book = Hibernate5LockLatestBook.get(id)
            assert book.title == 'last commit'
            assert book.version == 2
            true
        }

        cleanup:
        allowCompetingCommit?.countDown()
        allowRefreshCommit?.countDown()
        executor?.shutdownNow()
        assert executor == null || executor.awaitTermination(15, TimeUnit.SECONDS)
    }
}

@Entity
class Hibernate5LockLatestBook {
    Long id
    Long version
    String title
}

@Entity
class Hibernate5LockLatestRoutedBook {
    Long id
    Long version
    String title

    static mapping = {
        datasource 'ALL'
    }
}

@Entity
class Hibernate5LockLatestNonversionedBook {
    Long id
    String title

    static mapping = {
        version false
    }
}

@Entity
class Hibernate5LockLatestEmbeddedBook {
    Long id
    Long version
    String title
    Hibernate5LockLatestDetails details

    static embedded = ['details']
}

@DirtyCheck
class Hibernate5LockLatestDetails {
    String summary
    String language
}
