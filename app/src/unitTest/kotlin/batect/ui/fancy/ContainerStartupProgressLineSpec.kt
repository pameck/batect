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

package batect.ui.fancy

import batect.config.BuildImage
import batect.config.Container
import batect.config.PullImage
import batect.docker.DockerContainer
import batect.docker.DockerImage
import batect.docker.DockerImageBuildProgress
import batect.docker.DockerNetwork
import batect.docker.pull.DockerImagePullProgress
import batect.execution.model.events.ContainerBecameHealthyEvent
import batect.execution.model.events.ContainerCreatedEvent
import batect.execution.model.events.ContainerStartedEvent
import batect.execution.model.events.ImageBuildProgressEvent
import batect.execution.model.events.ImageBuiltEvent
import batect.execution.model.events.ImagePullProgressEvent
import batect.execution.model.events.ImagePulledEvent
import batect.execution.model.events.TaskNetworkCreatedEvent
import batect.execution.model.steps.BuildImageStep
import batect.execution.model.steps.CreateContainerStep
import batect.execution.model.steps.PullImageStep
import batect.execution.model.steps.RunContainerStep
import batect.execution.model.steps.StartContainerStep
import batect.os.Command
import batect.testutils.createForEachTest
import batect.testutils.equivalentTo
import batect.testutils.given
import batect.testutils.imageSourceDoesNotMatter
import batect.testutils.on
import batect.testutils.runForEachTest
import batect.ui.text.Text
import com.natpryce.hamkrest.assertion.assertThat
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object ContainerStartupProgressLineSpec : Spek({
    describe("a container startup progress line") {
        val dependencyA = Container("dependency-a", imageSourceDoesNotMatter())
        val dependencyB = Container("dependency-b", imageSourceDoesNotMatter())
        val dependencyC = Container("dependency-c", imageSourceDoesNotMatter())
        val otherContainer = Container("other-container", BuildImage("/other-build-dir"))
        val containerName = "some-container"

        given("the container's image comes from building an image") {
            val buildDirectory = "/some-image-dir"
            val container = Container(containerName, BuildImage(buildDirectory))
            val otherBuildDirectory = "/some-other-image-dir"

            val line: ContainerStartupProgressLine by createForEachTest {
                ContainerStartupProgressLine(container, setOf(dependencyA, dependencyB, dependencyC))
            }

            on("initial state") {
                val output by runForEachTest { line.print() }

                it("prints that the container is waiting to build its image") {
                    assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                }
            }

            describe("after receiving an 'image build starting' notification") {
                on("that notification being for this line's container") {
                    val step = BuildImageStep(buildDirectory, emptyMap(), emptySet())
                    beforeEachTest { line.onStepStarting(step) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is building") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": building image..."))))
                    }
                }

                on("that notification being for another container") {
                    val step = BuildImageStep(otherBuildDirectory, emptyMap(), emptySet())
                    beforeEachTest { line.onStepStarting(step) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }
            }

            describe("after receiving an 'image build progress' notification") {
                given("that notification is for this line's container") {
                    on("that notification containing image pull progress information") {
                        val event = ImageBuildProgressEvent(buildDirectory, DockerImageBuildProgress(1, 5, "FROM the-image:1.2.3", DockerImagePullProgress("downloading", 12, 20)))
                        beforeEachTest { line.onEventPosted(event) }
                        val output by runForEachTest { line.print() }

                        it("prints detailed build progress") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": building image: step 1 of 5: FROM the-image:1.2.3: downloading 12 B of 20 B (60%)"))))
                        }
                    }

                    on("that notification not containing image pull progress information") {
                        val event = ImageBuildProgressEvent(buildDirectory, DockerImageBuildProgress(2, 5, "COPY health-check.sh /tools/", null))
                        beforeEachTest { line.onEventPosted(event) }
                        val output by runForEachTest { line.print() }

                        it("prints detailed build progress") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": building image: step 2 of 5: COPY health-check.sh /tools/"))))
                        }
                    }
                }

                on("that notification being for another container") {
                    val event = ImageBuildProgressEvent(otherBuildDirectory, DockerImageBuildProgress(2, 5, "COPY health-check.sh /tools/", null))
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }
            }

            on("after receiving an 'image pull starting' notification") {
                val step = PullImageStep("some-image")
                beforeEachTest { line.onStepStarting(step) }
                val output by runForEachTest { line.print() }

                it("prints that the container is still waiting to build its image") {
                    assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                }
            }

            describe("after receiving an 'image built' notification") {
                describe("and that notification being for this line's container") {
                    val event = ImageBuiltEvent(buildDirectory, DockerImage("some-image"))

                    on("when the task network has already been created") {
                        beforeEachTest {
                            line.onEventPosted(TaskNetworkCreatedEvent(DockerNetwork("some-network")))
                            line.onEventPosted(event)
                        }

                        val output by runForEachTest { line.print() }

                        it("prints that the container is ready to be created") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": image built, ready to create container"))))
                        }
                    }

                    on("when the task network has not already been created") {
                        beforeEachTest { line.onEventPosted(event) }
                        val output by runForEachTest { line.print() }

                        it("prints that the container is ready to be created") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": image built, waiting for network to be ready..."))))
                        }
                    }
                }

                on("that notification being for another container") {
                    val event = ImageBuiltEvent(otherBuildDirectory, DockerImage("some-image"))
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }
            }

            on("after receiving a 'image pull completed' notification") {
                val event = ImagePulledEvent(DockerImage("some-image"))
                beforeEachTest { line.onEventPosted(event) }
                val output by runForEachTest { line.print() }

                it("prints that the container is still waiting to build its image") {
                    assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                }
            }

            describe("after receiving a 'task network created' notification") {
                val event = TaskNetworkCreatedEvent(DockerNetwork("some-network"))

                on("when the image has not started building yet") {
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }

                on("when the image is still building") {
                    beforeEachTest {
                        line.onStepStarting(BuildImageStep(buildDirectory, emptyMap(), emptySet()))
                        line.onEventPosted(event)
                    }

                    val output by runForEachTest { line.print() }

                    it("prints that the container is building") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": building image..."))))
                    }
                }

                on("when the image has been built") {
                    beforeEachTest {
                        line.onEventPosted(ImageBuiltEvent(buildDirectory, DockerImage("some-image")))
                        line.onEventPosted(event)
                    }

                    val output by runForEachTest { line.print() }

                    it("prints that the container is ready to be created") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": image built, ready to create container"))))
                    }
                }
            }

            describe("after receiving a 'creating container' notification") {
                on("that notification being for this line's container") {
                    val step = CreateContainerStep(container, Command.parse("some-command"), null, emptyMap(), emptySet(), emptySet(), DockerImage("some-image"), DockerNetwork("some-network"))
                    beforeEachTest { line.onStepStarting(step) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is being created") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": creating container..."))))
                    }
                }

                on("that notification being for another container") {
                    val step = CreateContainerStep(otherContainer, Command.parse("some-command"), null, emptyMap(), emptySet(), emptySet(), DockerImage("some-image"), DockerNetwork("some-network"))
                    beforeEachTest { line.onStepStarting(step) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }
            }

            describe("after receiving a 'container created' notification") {
                describe("on that notification being for this line's container") {
                    val event = ContainerCreatedEvent(container, DockerContainer("some-id"))

                    on("and none of the container's dependencies being ready") {
                        beforeEachTest { line.onEventPosted(event) }
                        val output by runForEachTest { line.print() }

                        it("prints that the container is waiting for the dependencies to be ready") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": waiting for dependencies ") + Text.bold(dependencyA.name) + Text(", ") + Text.bold(dependencyB.name) + Text(" and ") + Text.bold(dependencyC.name) + Text(" to be ready..."))))
                        }
                    }

                    on("and two of the container's dependencies not being ready") {
                        beforeEachTest {
                            line.onEventPosted(ContainerBecameHealthyEvent(dependencyA))
                            line.onEventPosted(event)
                        }
                        val output by runForEachTest { line.print() }

                        it("prints that the container is waiting for the dependencies to be ready") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": waiting for dependencies ") + Text.bold(dependencyB.name) + Text(" and ") + Text.bold(dependencyC.name) + Text(" to be ready..."))))
                        }
                    }

                    on("and one of the container's dependencies not being ready") {
                        beforeEachTest {
                            line.onEventPosted(ContainerBecameHealthyEvent(dependencyA))
                            line.onEventPosted(ContainerBecameHealthyEvent(dependencyB))
                            line.onEventPosted(event)
                        }

                        val output by runForEachTest { line.print() }

                        it("prints that the container is waiting for that dependency to be ready") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": waiting for dependency ") + Text.bold(dependencyC.name) + Text(" to be ready..."))))
                        }
                    }

                    on("and all of the container's dependencies are ready") {
                        beforeEachTest {
                            line.onEventPosted(ContainerBecameHealthyEvent(dependencyA))
                            line.onEventPosted(ContainerBecameHealthyEvent(dependencyB))
                            line.onEventPosted(ContainerBecameHealthyEvent(dependencyC))
                            line.onEventPosted(event)
                        }

                        val output by runForEachTest { line.print() }

                        it("prints that the container is ready to start") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to start"))))
                        }
                    }
                }

                on("that notification being for another container") {
                    val event = ContainerCreatedEvent(otherContainer, DockerContainer("some-id"))
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }
            }

            describe("after receiving a 'container starting' notification") {
                on("that notification being for this line's container") {
                    val step = StartContainerStep(container, DockerContainer("some-id"))
                    beforeEachTest { line.onStepStarting(step) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is starting") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": starting container..."))))
                    }
                }

                on("that notification being for another container") {
                    val step = StartContainerStep(otherContainer, DockerContainer("some-id"))
                    beforeEachTest { line.onStepStarting(step) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }
            }

            describe("after receiving a 'container started' notification") {
                on("that notification being for this line's container") {
                    val event = ContainerStartedEvent(container)
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is waiting to become healthy") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": container started, waiting for it to become healthy..."))))
                    }
                }

                on("that notification being for another container") {
                    val event = ContainerStartedEvent(otherContainer)
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }
            }

            describe("after receiving a 'container became healthy' notification") {
                on("that notification being for this line's container") {
                    val event = ContainerBecameHealthyEvent(container)
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container has finished starting up") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": running"))))
                    }
                }

                on("that notification being for another container") {
                    val event = ContainerBecameHealthyEvent(otherContainer)
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }
            }

            describe("after receiving a 'running container' notification") {
                describe("that notification being for this line's container") {
                    val step = RunContainerStep(container, DockerContainer("some-id"))

                    on("and the container does not have a command specified in the configuration file") {
                        beforeEachTest {
                            line.onStepStarting(CreateContainerStep(container, null, null, emptyMap(), emptySet(), emptySet(), DockerImage("some-image"), DockerNetwork("some-network")))
                            line.onStepStarting(step)
                        }

                        val output by runForEachTest { line.print() }

                        it("prints that the container has finished starting up") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": running"))))
                        }
                    }

                    on("and the container has a command specified in the configuration file") {
                        beforeEachTest {
                            line.onStepStarting(CreateContainerStep(container, Command.parse("some-command"), null, emptyMap(), emptySet(), emptySet(), DockerImage("some-image"), DockerNetwork("some-network")))
                            line.onStepStarting(step)
                        }

                        val output by runForEachTest { line.print() }

                        it("prints that the container has finished starting up and the command that it is running") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": running ") + Text.bold("some-command"))))
                        }
                    }

                    on("and the container has a command specified in the configuration file that contains line breaks") {
                        beforeEachTest {
                            line.onStepStarting(CreateContainerStep(container, Command.parse("some-command\ndo-stuff"), null, emptyMap(), emptySet(), emptySet(), DockerImage("some-image"), DockerNetwork("some-network")))
                            line.onStepStarting(step)
                        }

                        val output by runForEachTest { line.print() }

                        it("prints that the container has finished starting up and the command that it is running with the line breaks replaced with spaces") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": running ") + Text.bold("some-command do-stuff"))))
                        }
                    }

                    on("and another container has a command specified in the configuration file") {
                        beforeEachTest {
                            line.onStepStarting(CreateContainerStep(otherContainer, Command.parse("some-command"), null, emptyMap(), emptySet(), emptySet(), DockerImage("some-image"), DockerNetwork("some-network")))
                            line.onStepStarting(step)
                        }

                        val output by runForEachTest { line.print() }

                        it("prints that the container has finished starting up") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": running"))))
                        }
                    }
                }

                on("that notification being for another container") {
                    val step = RunContainerStep(otherContainer, DockerContainer("some-id"))
                    beforeEachTest { line.onStepStarting(step) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to build its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to build image"))))
                    }
                }
            }
        }

        given("the container's image comes from a pre-existing image that needs to be pulled") {
            val container = Container(containerName, PullImage("some-image"))

            val line: ContainerStartupProgressLine by createForEachTest {
                ContainerStartupProgressLine(container, setOf(dependencyA, dependencyB, dependencyC))
            }

            on("initial state") {
                val output by runForEachTest { line.print() }

                it("prints that the container is waiting to pull its image") {
                    assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to pull image"))))
                }
            }

            describe("after receiving an 'image pull starting' notification") {
                on("that notification being for this line's container's image") {
                    val step = PullImageStep("some-image")
                    beforeEachTest { line.onStepStarting(step) }
                    val output by runForEachTest { line.print() }

                    it("prints that the image is being pulled") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": pulling ") + Text.bold("some-image") + Text("..."))))
                    }
                }

                on("that notification being for another image") {
                    val step = PullImageStep("some-other-image")
                    beforeEachTest { line.onStepStarting(step) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to pull its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to pull image"))))
                    }
                }
            }

            describe("after receiving an 'image pull progress' notification") {
                beforeEachTest {
                    line.onStepStarting(PullImageStep("some-image"))
                }

                on("that notification being for this line's container's image") {
                    beforeEachTest { line.onEventPosted(ImagePullProgressEvent("some-image", DockerImagePullProgress("extracting", 10, 20))) }
                    val output by runForEachTest { line.print() }

                    it("prints that the image is being pulled with detailed progress information") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": pulling ") + Text.bold("some-image") + Text(": extracting 10 B of 20 B (50%)"))))
                    }
                }

                on("that notification being for another image") {
                    beforeEachTest { line.onEventPosted(ImagePullProgressEvent("some-other-image", DockerImagePullProgress("Doing something", 10, 20))) }
                    val output by runForEachTest { line.print() }

                    it("prints that the image is being pulled") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": pulling ") + Text.bold("some-image") + Text("..."))))
                    }
                }
            }

            describe("after receiving an 'image pulled' notification") {
                describe("and that notification being for this line's container's image") {
                    val event = ImagePulledEvent(DockerImage("some-image"))

                    on("when the task network has already been created") {
                        beforeEachTest {
                            line.onEventPosted(TaskNetworkCreatedEvent(DockerNetwork("some-network")))
                            line.onEventPosted(event)
                        }

                        val output by runForEachTest { line.print() }

                        it("prints that the container is ready to be created") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": image pulled, ready to create container"))))
                        }
                    }

                    on("when the task network has not already been created") {
                        beforeEachTest { line.onEventPosted(event) }
                        val output by runForEachTest { line.print() }

                        it("prints that the container is ready to be created") {
                            assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": image pulled, waiting for network to be ready..."))))
                        }
                    }
                }

                on("that notification being for another image") {
                    val event = ImagePulledEvent(DockerImage("some-other-image"))
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to pull its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to pull image"))))
                    }
                }
            }

            describe("after receiving a 'task network created' notification") {
                val event = TaskNetworkCreatedEvent(DockerNetwork("some-network"))

                on("when the image pull has not started yet") {
                    beforeEachTest { line.onEventPosted(event) }
                    val output by runForEachTest { line.print() }

                    it("prints that the container is still waiting to pull its image") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": ready to pull image"))))
                    }
                }

                on("when the image is still being pulled but no progress information has been received yet") {
                    beforeEachTest {
                        line.onStepStarting(PullImageStep("some-image"))
                        line.onEventPosted(event)
                    }

                    val output by runForEachTest { line.print() }

                    it("prints that the container is being pulled") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": pulling ") + Text.bold("some-image") + Text("..."))))
                    }
                }

                on("when the image is being pulled and some progress information has been received") {
                    beforeEachTest {
                        line.onStepStarting(PullImageStep("some-image"))
                        line.onEventPosted(ImagePullProgressEvent("some-image", DockerImagePullProgress("extracting", 10, 20)))
                        line.onEventPosted(event)
                    }

                    val output by runForEachTest { line.print() }

                    it("prints that the image is being pulled with detailed progress information") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": pulling ") + Text.bold("some-image") + Text(": extracting 10 B of 20 B (50%)"))))
                    }
                }

                on("when the image has been pulled") {
                    beforeEachTest {
                        line.onEventPosted(ImagePulledEvent(DockerImage("some-image")))
                        line.onEventPosted(event)
                    }

                    val output by runForEachTest { line.print() }

                    it("prints that the container is ready to be created") {
                        assertThat(output, equivalentTo(Text.white(Text.bold(containerName) + Text(": image pulled, ready to create container"))))
                    }
                }
            }
        }
    }
})
