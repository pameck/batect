package batect.model.steps

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.doThrow
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import batect.config.Container
import batect.docker.ContainerCreationFailedException
import batect.docker.ContainerDoesNotExistException
import batect.docker.ContainerHealthCheckException
import batect.docker.ContainerRemovalFailedException
import batect.docker.ContainerStartFailedException
import batect.docker.ContainerStopFailedException
import batect.docker.DockerClient
import batect.docker.DockerContainer
import batect.docker.DockerContainerRunResult
import batect.docker.DockerImage
import batect.docker.DockerNetwork
import batect.docker.HealthStatus
import batect.docker.ImageBuildFailedException
import batect.docker.NetworkCreationFailedException
import batect.docker.NetworkDeletionFailedException
import batect.model.events.ContainerBecameHealthyEvent
import batect.model.events.ContainerCreatedEvent
import batect.model.events.ContainerCreationFailedEvent
import batect.model.events.ContainerDidNotBecomeHealthyEvent
import batect.model.events.ContainerRemovalFailedEvent
import batect.model.events.ContainerRemovedEvent
import batect.model.events.ContainerStartFailedEvent
import batect.model.events.ContainerStartedEvent
import batect.model.events.ContainerStopFailedEvent
import batect.model.events.ContainerStoppedEvent
import batect.model.events.ImageBuildFailedEvent
import batect.model.events.ImageBuiltEvent
import batect.model.events.RunningContainerExitedEvent
import batect.model.events.TaskEventSink
import batect.model.events.TaskNetworkCreatedEvent
import batect.model.events.TaskNetworkCreationFailedEvent
import batect.model.events.TaskNetworkDeletedEvent
import batect.model.events.TaskNetworkDeletionFailedEvent
import batect.model.events.TaskStartedEvent
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on

object TaskStepRunnerSpec : Spek({
    describe("a task step runner") {
        val eventSink = mock<TaskEventSink>()
        val dockerClient = mock<DockerClient>()
        val runner = TaskStepRunner(dockerClient)

        beforeEachTest {
            reset(eventSink)
            reset(dockerClient)
        }

        describe("running steps") {
            on("running a 'begin task' step") {
                val step = BeginTaskStep
                runner.run(step, eventSink)

                it("emits a 'task started' event") {
                    verify(eventSink).postEvent(TaskStartedEvent)
                }
            }

            describe("running a 'build image' step") {
                val container = Container("some-container", "/some-build-dir")
                val step = BuildImageStep("some-project-name", container)

                on("when building the image succeeds") {
                    val image = DockerImage("some-image")
                    whenever(dockerClient.build("some-project-name", container)).thenReturn(image)

                    runner.run(step, eventSink)

                    it("emits a 'image built' event") {
                        verify(eventSink).postEvent(ImageBuiltEvent(container, image))
                    }
                }

                on("when building the image fails") {
                    whenever(dockerClient.build("some-project-name", container)).thenThrow(ImageBuildFailedException("Something went wrong."))

                    runner.run(step, eventSink)

                    it("emits a 'image build failed' event") {
                        verify(eventSink).postEvent(ImageBuildFailedEvent(container, "Image build failed. Output from Docker was: Something went wrong."))
                    }
                }
            }

            describe("running a 'create task network' step") {
                val step = CreateTaskNetworkStep

                on("when creating the network succeeds") {
                    val network = DockerNetwork("some-network")
                    whenever(dockerClient.createNewBridgeNetwork()).doReturn(network)

                    runner.run(step, eventSink)

                    it("emits a 'network created' event") {
                        verify(eventSink).postEvent(TaskNetworkCreatedEvent(network))
                    }
                }

                on("when creating the network fails") {
                    whenever(dockerClient.createNewBridgeNetwork()).doThrow(NetworkCreationFailedException("Something went wrong."))

                    runner.run(step, eventSink)

                    it("emits a 'network creation failed' event") {
                        verify(eventSink).postEvent(TaskNetworkCreationFailedEvent("Something went wrong."))
                    }
                }
            }

            describe("running a 'create container' step") {
                val container = Container("some-container", "/some-build-dir")
                val command = "do-stuff"
                val image = DockerImage("some-image")
                val network = DockerNetwork("some-network")
                val step = CreateContainerStep(container, command, image, network)

                on("when creating the container succeeds") {
                    val dockerContainer = DockerContainer("some-id", "some-container")
                    whenever(dockerClient.create(container, command, image, network)).doReturn(dockerContainer)

                    runner.run(step, eventSink)

                    it("emits a 'container created' event") {
                        verify(eventSink).postEvent(ContainerCreatedEvent(container, dockerContainer))
                    }
                }

                on("when creating the container fails") {
                    whenever(dockerClient.create(container, command, image, network)).doThrow(ContainerCreationFailedException("Something went wrong."))

                    runner.run(step, eventSink)

                    it("emits a 'container creation failed' event") {
                        verify(eventSink).postEvent(ContainerCreationFailedEvent(container, "Something went wrong."))
                    }
                }
            }

            on("running a 'run container' step") {
                val container = Container("some-container", "/some-build-dir")
                val dockerContainer = DockerContainer("some-id", "some-container")
                val step = RunContainerStep(container, dockerContainer)

                whenever(dockerClient.run(dockerContainer)).doReturn(DockerContainerRunResult(200))

                runner.run(step, eventSink)

                it("emits a 'running container exited' event") {
                    verify(eventSink).postEvent(RunningContainerExitedEvent(container, 200))
                }
            }

            describe("running a 'start container' step") {
                val container = Container("some-container", "/some-build-dir")
                val dockerContainer = DockerContainer("some-id", "some-container")
                val step = StartContainerStep(container, dockerContainer)

                on("when starting the container succeeds") {
                    runner.run(step, eventSink)

                    it("starts the container") {
                        verify(dockerClient).start(dockerContainer)
                    }

                    it("emits a 'container started' event") {
                        verify(eventSink).postEvent(ContainerStartedEvent(container))
                    }
                }

                on("when starting the container fails") {
                    whenever(dockerClient.start(dockerContainer)).thenThrow(ContainerStartFailedException("some-id", "Something went wrong"))

                    runner.run(step, eventSink)

                    it("emits a 'container start failed' event") {
                        verify(eventSink).postEvent(ContainerStartFailedEvent(container, "Something went wrong"))
                    }
                }
            }

            describe("running a 'stop container' step") {
                val container = Container("some-container", "/some-build-dir")
                val dockerContainer = DockerContainer("some-id", "some-container")
                val step = StopContainerStep(container, dockerContainer)

                on("when stopping the container succeeds") {
                    runner.run(step, eventSink)

                    it("stops the container") {
                        verify(dockerClient).stop(dockerContainer)
                    }

                    it("emits a 'container stopped' event") {
                        verify(eventSink).postEvent(ContainerStoppedEvent(container))
                    }
                }

                on("when stopping the container fails") {
                    whenever(dockerClient.stop(dockerContainer)).thenThrow(ContainerStopFailedException("some-id", "Something went wrong"))

                    runner.run(step, eventSink)

                    it("emits a 'container stop failed' event") {
                        verify(eventSink).postEvent(ContainerStopFailedEvent(container, "Something went wrong"))
                    }
                }
            }

            describe("running a 'clean up container' step") {
                val container = Container("some-container", "/some-build-dir")
                val dockerContainer = DockerContainer("some-id", "some-container")
                val step = CleanUpContainerStep(container, dockerContainer)

                on("when cleaning up the container succeeds") {
                    runner.run(step, eventSink)

                    it("removes the container") {
                        verify(dockerClient).forciblyRemove(dockerContainer)
                    }

                    it("emits a 'container removed' event") {
                        verify(eventSink).postEvent(ContainerRemovedEvent(container))
                    }
                }

                on("when cleaning up the container fails") {
                    whenever(dockerClient.forciblyRemove(dockerContainer)).thenThrow(ContainerRemovalFailedException("some-id", "Something went wrong"))

                    runner.run(step, eventSink)

                    it("emits a 'container removal failed' event") {
                        verify(eventSink).postEvent(ContainerRemovalFailedEvent(container, "Something went wrong"))
                    }
                }

                on("when the container does not exist") {
                    whenever(dockerClient.forciblyRemove(dockerContainer)).thenThrow(ContainerDoesNotExistException("Some message"))

                    runner.run(step, eventSink)

                    it("emits a 'container removed' event") {
                        verify(eventSink).postEvent(ContainerRemovedEvent(container))
                    }
                }
            }

            describe("running a 'remove container' step") {
                val container = Container("some-container", "/some-build-dir")
                val dockerContainer = DockerContainer("some-id", "some-container")
                val step = RemoveContainerStep(container, dockerContainer)

                on("when removing the container succeeds") {
                    runner.run(step, eventSink)

                    it("removes the container") {
                        verify(dockerClient).remove(dockerContainer)
                    }

                    it("emits a 'container removed' event") {
                        verify(eventSink).postEvent(ContainerRemovedEvent(container))
                    }
                }

                on("when removing the container fails") {
                    whenever(dockerClient.remove(dockerContainer)).thenThrow(ContainerRemovalFailedException("some-id", "Something went wrong"))

                    runner.run(step, eventSink)

                    it("emits a 'container removal failed' event") {
                        verify(eventSink).postEvent(ContainerRemovalFailedEvent(container, "Something went wrong"))
                    }
                }

                on("when the container does not exist") {
                    whenever(dockerClient.remove(dockerContainer)).thenThrow(ContainerDoesNotExistException("Some message"))

                    runner.run(step, eventSink)

                    it("emits a 'container removed' event") {
                        verify(eventSink).postEvent(ContainerRemovedEvent(container))
                    }
                }
            }

            describe("running a 'wait for container to become healthy' step") {
                val container = Container("some-container", "/some-build-dir")
                val dockerContainer = DockerContainer("some-id", "some-container")
                val step = WaitForContainerToBecomeHealthyStep(container, dockerContainer)

                on("when the container has no health check") {
                    whenever(dockerClient.waitForHealthStatus(dockerContainer)).doReturn(HealthStatus.NoHealthCheck)

                    runner.run(step, eventSink)

                    it("emits a 'container became healthy' event") {
                        verify(eventSink).postEvent(ContainerBecameHealthyEvent(container))
                    }
                }

                on("when the container becomes healthy") {
                    whenever(dockerClient.waitForHealthStatus(dockerContainer)).doReturn(HealthStatus.BecameHealthy)

                    runner.run(step, eventSink)

                    it("emits a 'container became healthy' event") {
                        verify(eventSink).postEvent(ContainerBecameHealthyEvent(container))
                    }
                }

                on("when the container becomes unhealthy") {
                    whenever(dockerClient.waitForHealthStatus(dockerContainer)).doReturn(HealthStatus.BecameUnhealthy)

                    runner.run(step, eventSink)

                    it("emits a 'container did not become healthy' event") {
                        verify(eventSink).postEvent(ContainerDidNotBecomeHealthyEvent(container, "The configured health check did not report the container as healthy within the timeout period."))
                    }
                }

                on("when the container exits before reporting a health status") {
                    whenever(dockerClient.waitForHealthStatus(dockerContainer)).doReturn(HealthStatus.Exited)

                    runner.run(step, eventSink)

                    it("emits a 'container did not become healthy' event") {
                        verify(eventSink).postEvent(ContainerDidNotBecomeHealthyEvent(container, "The container exited before becoming healthy."))
                    }
                }

                on("when waiting for the container's health status fails") {
                    whenever(dockerClient.waitForHealthStatus(dockerContainer)).doThrow(ContainerHealthCheckException("Something went wrong"))

                    runner.run(step, eventSink)

                    it("emits a 'container did not become healthy' event") {
                        verify(eventSink).postEvent(ContainerDidNotBecomeHealthyEvent(container, "Waiting for the container's health status failed: Something went wrong"))
                    }
                }
            }

            describe("running a 'delete task network' step") {
                val network = DockerNetwork("some-network")
                val step = DeleteTaskNetworkStep(network)

                on("when deleting the network succeeds") {
                    runner.run(step, eventSink)

                    it("deletes the network") {
                        verify(dockerClient).deleteNetwork(network)
                    }

                    it("emits a 'network deleted' event") {
                        verify(eventSink).postEvent(TaskNetworkDeletedEvent)
                    }
                }

                on("when removing the container fails") {
                    whenever(dockerClient.deleteNetwork(network)).thenThrow(NetworkDeletionFailedException("some-network", "Something went wrong"))

                    runner.run(step, eventSink)

                    it("emits a 'network deletion failed' event") {
                        verify(eventSink).postEvent(TaskNetworkDeletionFailedEvent("Something went wrong"))
                    }
                }
            }

            on("running a 'display task failure' step") {
                val step = DisplayTaskFailureStep("Something went wrong.")
                runner.run(step, eventSink)

                it("does not emit any events") {
                    verify(eventSink, never()).postEvent(any())
                }
            }

            on("running a 'finish task' step") {
                val step = FinishTaskStep(123)
                runner.run(step, eventSink)

                it("does not emit any events") {
                    verify(eventSink, never()).postEvent(any())
                }
            }
        }
    }
})