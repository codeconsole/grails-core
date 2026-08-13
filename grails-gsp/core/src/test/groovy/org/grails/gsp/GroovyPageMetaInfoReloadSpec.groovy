/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.gsp

import java.security.PrivilegedAction

import spock.lang.Specification
import spock.lang.TempDir

import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource

import org.grails.gsp.compiler.GroovyPageParser

/**
 * Reload-staleness behaviour of {@link GroovyPageMetaInfo}.
 *
 * A page compiled by {@code GroovyPageCompiler} records a checksum of its source rather than the source's
 * modification time, which git does not preserve across a checkout. Staleness is therefore decided by
 * comparing content, falling back to the timestamp for pages compiled before the checksum existed.
 *
 * Each feature uses a fresh {@code GroovyPageMetaInfo}, because the result of a check is cached for
 * {@code grails.gsp.reload.interval} milliseconds.
 */
class GroovyPageMetaInfoReloadSpec extends Specification {

    private static final String PAGE_CONTENT = '<html><body>hi</body></html>'

    @TempDir
    File tempDir

    private Resource sourcePage(String content = PAGE_CONTENT) {
        File page = new File(this.tempDir, 'index.gsp')
        page.text = content
        new FileSystemResource(page)
    }

    private static String checksumOf(Resource resource) {
        GroovyPageParser.checksumOf(resource.contentAsByteArray)
    }

    private static PrivilegedAction<Resource> callableFor(Resource resource) {
        { -> resource } as PrivilegedAction
    }

    void 'a page whose recorded checksum matches its source is not reported as stale'() {
        given: 'a precompiled page recording the checksum of the source on disk'
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.sourceChecksum = checksumOf(resource)

        expect:
        !metaInfo.shouldReload(callableFor(resource))
    }

    void 'a page whose source no longer matches its recorded checksum is reported as stale'() {
        given: 'a precompiled page whose source has since been edited'
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.sourceChecksum = checksumOf(resource)
        resource.getFile().text = '<html><body>edited</body></html>'

        expect:
        metaInfo.shouldReload(callableFor(resource))
    }

    void 'a source that was touched but not edited is not reported as stale'() {
        given: 'a page whose source carries a modification time nothing like the one it was compiled at'
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.sourceChecksum = checksumOf(resource)
        assert resource.getFile().setLastModified(resource.getFile().lastModified() + 86_400_000L)

        expect: 'content decides, so a fresh checkout does not force every page to recompile'
        !metaInfo.shouldReload(callableFor(resource))
    }

    void 'a recorded checksum decides staleness even when a timestamp is also recorded'() {
        given: 'a page carrying both a matching checksum and a timestamp long predating its source'
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.sourceChecksum = checksumOf(resource)
        metaInfo.lastModified = resource.getFile().lastModified() - 86_400_000L

        expect: 'the content decides, so the timestamp alone cannot force a reload'
        !metaInfo.shouldReload(callableFor(resource))
    }

    void 'a recorded checksum reports an edit even when the timestamp agrees'() {
        given: 'a page whose source was edited without its timestamp moving'
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.sourceChecksum = checksumOf(resource)
        long originalTimestamp = resource.getFile().lastModified()
        metaInfo.lastModified = originalTimestamp
        resource.getFile().text = '<html><body>edited</body></html>'
        assert resource.getFile().setLastModified(originalTimestamp)

        expect: 'the content decides, so an agreeing timestamp cannot mask the edit'
        metaInfo.shouldReload(callableFor(resource))
    }

    void 'an edit within the timestamp granularity window is still reported as stale'() {
        given: 'an edited source whose modification time is unchanged, as a rapid rewrite can leave it'
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.sourceChecksum = checksumOf(resource)
        long originalTimestamp = resource.getFile().lastModified()
        resource.getFile().text = '<html><body>edited</body></html>'
        assert resource.getFile().setLastModified(originalTimestamp)

        expect: 'the timestamp comparison would have missed this; the checksum does not'
        metaInfo.shouldReload(callableFor(resource))
    }

    void 'a page recorded with a timestamp older than its source is reported as stale'() {
        given: 'a page compiled before SOURCE_CHECKSUM existed, so only a timestamp is recorded'
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.lastModified = resource.getFile().lastModified() - 60_000L

        expect: 'the pre-existing staleness detection still applies to it'
        metaInfo.shouldReload(callableFor(resource))
    }

    void 'a page recorded with the same timestamp as its source is not reported as stale'() {
        given:
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.lastModified = resource.getFile().lastModified()

        expect:
        !metaInfo.shouldReload(callableFor(resource))
    }

    void 'a page with neither a checksum nor a timestamp is not reported as stale'() {
        given: 'nothing was recorded to compare the source against'
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.lastModified = 0L

        expect: 'the page is left in place rather than reported as changed on every single check'
        !metaInfo.shouldReload(callableFor(resource))
    }

    void 'a page whose timestamp could not be established reloads once and recovers'() {
        given: 'a runtime-compiled page that recorded -1, as establishLastModified yields on an unreadable resource'
        Resource resource = sourcePage()
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.lastModified = -1L

        expect: 'it is reported stale so the first readable check reloads it, rather than stranding it until restart'
        metaInfo.shouldReload(callableFor(resource))
    }

    void 'a missing source resource never triggers a reload'() {
        given:
        Resource resource = new FileSystemResource(new File(this.tempDir, 'absent.gsp'))
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.sourceChecksum = 'a-checksum-for-a-page-with-no-source'

        expect:
        !metaInfo.shouldReload(callableFor(resource))
    }

    void 'a page with no way to resolve its source never triggers a reload'() {
        given: 'the locator supplies no callable, as it does for views inside a binary plugin jar'
        GroovyPageMetaInfo metaInfo = new GroovyPageMetaInfo()
        metaInfo.sourceChecksum = 'a-checksum-for-a-page-shipped-in-a-jar'

        expect:
        !metaInfo.shouldReload(null)
    }
}
