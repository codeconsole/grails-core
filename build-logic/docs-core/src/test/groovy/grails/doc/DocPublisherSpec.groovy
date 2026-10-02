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
package grails.doc

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

class DocPublisherSpec extends Specification {

    @Shared
    @TempDir
    File workspace

    @Shared
    File guideDir

    void setupSpec() {
        File sourceDir = new File(workspace, 'src')
        File guideSourceDir = new File(sourceDir, 'guide')
        new File(guideSourceDir, 'second').mkdirs()
        new File(guideSourceDir, 'toc.yml').text = '''\
            first: First
            second:
              title: Second
              secondChild: Second Child
            third: Third
            '''.stripIndent()
        new File(guideSourceDir, 'first.adoc').text = '''\
            [[localAnchor]]
            Links: <<second>>, <<secondChild>>, <<customAnchor,an anchor>>, <<sharedAnchor,a shared anchor>>,
            <<localAnchor,a local anchor>> and <<missingAnchor,a missing anchor>>.
            '''.stripIndent()
        new File(guideSourceDir, 'second.adoc').text = '''\
            [[customAnchor]]
            An anchored paragraph.

            [[sharedAnchor]]
            The first chapter defining this anchor.
            '''.stripIndent()
        new File(guideSourceDir, 'second/secondChild.adoc').text = 'A sub-section.\n'
        new File(guideSourceDir, 'third.adoc').text = '''\
            [[sharedAnchor]]
            A later chapter defining the same anchor.
            '''.stripIndent()

        File targetDir = new File(workspace, 'guide-output')
        File workDir = new File(workspace, 'work')
        workDir.mkdirs()
        Properties engineProperties = new Properties()
        engineProperties.setProperty('title', 'Publisher Guide')
        DocPublisher publisher = new DocPublisher(sourceDir, targetDir)
        publisher.asciidoc = true
        publisher.workDir = workDir
        publisher.version = '1.2.3'
        publisher.engineProperties = engineProperties
        publisher.publish()
        guideDir = new File(targetDir, 'guide')
    }

    void 'points a cross reference on a chapter page at the chapter page that defines its target'() {
        when:
        String first = new File(guideDir, 'first.html').text

        then: 'references to another chapter lead to that chapter page'
        first.contains('href="second.html#second"')
        first.contains('href="second.html#secondChild"')
        first.contains('href="second.html#customAnchor"')

        and: 'an anchor defined in several chapters leads to the first, as in the single-page guide'
        first.contains('href="second.html#sharedAnchor"')
        !first.contains('href="third.html#sharedAnchor"')

        and: 'references within the page, and to anchors no page defines, are left alone'
        first.contains('href="#localAnchor"')
        first.contains('href="#missingAnchor"')
    }

    void 'keeps cross references in the single-page guide on that page'() {
        when:
        String single = new File(guideDir, 'single.html').text

        then: 'the single-page guide holds every target, so its links stay fragments'
        single.contains('href="#second"')
        single.contains('href="#customAnchor"')
        !single.contains('href="second.html#')
    }
}
