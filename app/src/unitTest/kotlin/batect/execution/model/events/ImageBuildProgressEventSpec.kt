/*
   Copyright 2017-2019 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package batect.execution.model.events

import batect.docker.DockerImageBuildProgress
import batect.docker.pull.DockerImagePullProgress
import batect.testutils.given
import batect.testutils.on
import com.natpryce.hamkrest.equalTo
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object ImageBuildProgressEventSpec : Spek({
    describe("an 'image build progress' event") {
        given("it has some image pull progress information") {
            val event = ImageBuildProgressEvent("/some-build-dir", DockerImageBuildProgress(1, 10, "Something is happening", DockerImagePullProgress("downloading", 12, 20)))

            on("toString()") {
                it("returns a human-readable representation of itself") {
                    com.natpryce.hamkrest.assertion.assertThat(event.toString(), equalTo("ImageBuildProgressEvent(build directory: '/some-build-dir', current step: 1, total steps: 10, message: 'Something is happening', pull progress: 'downloading 12 B of 20 B (60%)')"))
                }
            }
        }

        given("it has no image pull progress information") {
            val event = ImageBuildProgressEvent("/some-build-dir", DockerImageBuildProgress(1, 10, "Something is happening", null))

            on("toString()") {
                it("returns a human-readable representation of itself") {
                    com.natpryce.hamkrest.assertion.assertThat(event.toString(), equalTo("ImageBuildProgressEvent(build directory: '/some-build-dir', current step: 1, total steps: 10, message: 'Something is happening', pull progress: null)"))
                }
            }
        }
    }
})
