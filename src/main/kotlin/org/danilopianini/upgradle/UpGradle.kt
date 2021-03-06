package org.danilopianini.upgradle

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.toml
import com.uchuhimo.konf.source.yaml
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.danilopianini.upgradle.api.Credentials
import org.danilopianini.upgradle.api.Credentials.Companion.authenticated
import org.danilopianini.upgradle.api.Module
import org.danilopianini.upgradle.api.Module.StringExtensions.asUpGradleModule
import org.danilopianini.upgradle.api.Operation
import org.danilopianini.upgradle.config.Configurator
import org.eclipse.egit.github.core.Repository
import org.eclipse.egit.github.core.RepositoryBranch
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess

class UpGradle(configuration: Config.() -> Config = { from.yaml.resource("upgradle.yml") }) {
    val configuration = Configurator.load(configuration)

    fun runModule(repository: Repository, branch: RepositoryBranch, module: Module, credentials: Credentials) {
        val user = repository.owner.login
        logger.info("Running ${module.name} on $user/${repository.name} on branch ${branch.name}")
        val workdirPrefix = "upgradle-${user}_${repository.name}_${branch.name}_${module.name}"
        val destination = createTempDir(workdirPrefix)
        logger.info("Working inside ${destination.absolutePath}")
        val git = repository.clone(branch, destination, credentials)
        val branches = git.branchList().call().map { it.name }
        logger.info("Available branches: $branches")
        module(destination)
                .asSequence()
                .filterNot { it.branch in branches }
                .forEach { update ->
                    prepareRepository(git, branch, update)
                    // Run the update operation
                    logger.info("Running update...")
                    val changes = update()
                    logger.info("Changes: {}", changes)
                    git.add(destination, changes)
                    // Commit changes
                    git.commit(update.commitMessage)
                    // Push the new branch
                    logger.info("Pushing ${update.branch}...")
                    val pushResults = git.pushTo(update.branch, credentials)
                    // If push ok, create a pull request
                    if (pushResults.isNotEmpty() && pushResults.all { it.status == RemoteRefUpdate.Status.OK }) {
                        logger.info("Push successful, creating a pull request")
                        try {
                            val pullRequest = repository.createPullRequest(
                                    update,
                                    head = update.branch,
                                    base = branch.name,
                                    credentials = credentials
                            )
                            logger.info("Pull request #${pullRequest.number} opened ${update.branch} -> ${branch.name}")
                            repository.applyLabels(configuration.labels, pullRequest, credentials)
                        } catch (requestException: RequestException) {
                            when (requestException.status) {
                                UNPROCESSABLE_ENTITY -> println(requestException.message)
                                else -> throw requestException
                            }
                        }
                    } else {
                        logger.error("Push failed.")
                        pushResults.map(Any::toString).forEach(logger::error)
                    }
                }
        destination.deleteRecursively()
    }

    companion object {

        private const val UNPROCESSABLE_ENTITY = 422
        internal val logger = LoggerFactory.getLogger(UpGradle::class.java)

        private fun upgradleFromArguments(args: Array<String>) = when (args.size) {
            0 -> UpGradle()
            1 -> File(args[0]).let { file ->
                val path = file.absolutePath
                if (!file.exists()) {
                    println("File $path does not exist")
                    exitProcess(2)
                }
                if (file.isDirectory) {
                    println("File $path is a directory")
                    exitProcess(3)
                }
                UpGradle {
                    when (file.extension) {
                        in setOf("yml", "yaml") -> from.yaml
                        "json" -> from.json
                        "toml" -> from.toml
                        else -> {
                            println("Unsupported file type: ${file.extension}")
                            exitProcess(4)
                        }
                    }.file(file)
                }
            }
            else -> {
                println("A single parameter is required with the path to the configuration file.")
                println("If no parameter is provided, the upgradle.yml will get loaded from classpath")
                exitProcess(1)
            }
        }

        fun Boolean.then(block: () -> Unit): Boolean {
            if (this) {
                block()
            }
            return this
        }

        fun prepareRepository(git: Git, branch: RepositoryBranch, update: Operation): Boolean =
            git.branchList().call().none { it.name == branch.name }.then {
                logger.info("Checking out ${branch.name}")
                git.checkout().setName(branch.name).call()
                logger.info("Resetting the repo status")
                git.reset().setMode(ResetCommand.ResetType.HARD).call()
                // Start a new working branch
                git.checkout().setCreateBranch(true).setName(update.branch).call()
            }

        @JvmStatic
        fun main(args: Array<String>) {
            val upgradle: UpGradle = upgradleFromArguments(args)
            val credentials = Credentials.loadGitHubCredentials()
            val repositoryService = RepositoryService().authenticated(credentials)
            runBlocking {
                upgradle.configuration.selectedRemoteBranchesFor(repositoryService).forEach { (repository, branch) ->
                    upgradle.configuration.modules
                        .map { it.asUpGradleModule }
                        .forEach { module ->
                            launch {
                                upgradle.runModule(repository, branch, module, credentials)
                            }
                        }
                }
                logger.info("Done")
            }
        }
    }
}
