/*
 * Copyright 2018, WuxiNextcode
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.cloud.gcp.pipelines

import java.nio.file.Path

import com.google.cloud.storage.contrib.nio.CloudStoragePath
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Nextflow
import nextflow.exception.AbortOperationException
import nextflow.executor.Executor
import nextflow.executor.SupportedScriptTypes
import nextflow.extension.FilesEx
import nextflow.processor.TaskHandler
import nextflow.processor.TaskMonitor
import nextflow.processor.TaskPollingMonitor
import nextflow.processor.TaskRun
import nextflow.script.ScriptType
import nextflow.util.Duration
import nextflow.util.ServiceName

/**
 * Google Pipelines Executor.
 *
 * https://cloud.google.com/genomics/
 *
 * @author Ólafur Haukur Flygenring <olafurh@wuxinextcode.com>
 */
@Slf4j
@CompileStatic
@ServiceName('gpapi')
@SupportedScriptTypes(ScriptType.SCRIPTLET)
class GooglePipelinesExecutor extends Executor {

    static private GooglePipelinesConfiguration pipelineConfig

    GooglePipelinesHelper helper

    GooglePipelinesExecutor() {
        this.helper = new GooglePipelinesHelper()
    }

    @Override
    final boolean isContainerNative() {
        return true
    }

    @Override
    final Path getWorkDir() {
        session.bucketDir ?: session.workDir
    }

    @Override
    void register() {
        super.register()
        pipelineConfig = validateConfiguration()
        log.debug "[GOOGLE PIPELINE] Pipelines Configuration: '$pipelineConfig'"
    }

    @Override
    protected TaskMonitor createTaskMonitor() {
        TaskPollingMonitor.create(session, name, 1000, Duration.of('10 sec'))
    }

    @Override
    TaskHandler createTaskHandler(TaskRun task) {
        return new GooglePipelinesTaskHandler(task, this, pipelineConfig)
    }

    GooglePipelinesConfiguration validateConfiguration() {

        //Make sure that the workdir is a GS Bucket
        if (!(getWorkDir() instanceof CloudStoragePath)) {
            session.abort()
            throw new AbortOperationException("When using `$name` executor a GCE bucket must be provided as a working directory -- Add the option `-w gs://<your-bucket/path>` to your run command line or specify a workDir in your config file")
        }

        //Check for the existence of all required configuration for our executor
        def requiredConfigs = ["gcp.project"]

        for( String it : requiredConfigs ) {
            if (!session.config.navigate(it)) {
                session.abort()
                throw new AbortOperationException("Required config value '$it' for executor $name is not defined -- Please add it to your process or nextflow configuration file")
            }
        }

        //check if we have one of the mutual exclusive zone or region specified
        if(!session.config.navigate("gcp.zone") && !session.config.navigate("gcp.region")){
            session.abort()
            throw new AbortOperationException("Missing configuration value 'gcp.zone' or 'gcp.region'")
        }

        //check if we have one of the mutual exclusive zone or region specified
        if(session.config.navigate("gcp.zone") && session.config.navigate("gcp.region")){
            session.abort()
            throw new AbortOperationException("You can't specify both 'gcp.zone' and 'gcp.region' configuration parameters -- Please remove one of them from your configuration")
        }


        def path = session.config.navigate('env.PATH')
        if( path ) {
            log.warn "Environment PATH defined in config file is ignored by Google Pipeline executor"
        }

        /*
         * upload local binaries
         */
        def disableBinDir = session.getExecConfigProp(name, 'disableRemoteBinDir', false)
        Path remoteBinDir = null
        if( session.binDir && !disableBinDir ) {
            def cloudPath = Nextflow.tempDir() + "/" //need the ending slash to mark it as a directory since it doesn't exist yet
            log.info "Uploading local `bin` scripts folder to ${cloudPath.toUriString()}bin"
            remoteBinDir = FilesEx.copyTo(session.binDir, cloudPath)
        }

        def zones = (session.config.navigate("gcp.zone") as String)?.split(",")?.toList()
        def regions = (session.config.navigate("gcp.region") as String)?.split(",")?.toList()


        new GooglePipelinesConfiguration(
                session.config.navigate("gcp.project") as String,
                zones,
                regions,
                remoteBinDir,
                session.config.navigate("cloud.preemptible") as boolean
        )
    }
}