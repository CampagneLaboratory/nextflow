/*
 * Copyright (c) 2013-2015, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2015, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.processor
import groovy.transform.InheritConstructors
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowQueue
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel
import groovyx.gpars.dataflow.operator.DataflowEventAdapter
import groovyx.gpars.dataflow.operator.DataflowOperator
import groovyx.gpars.dataflow.operator.DataflowProcessor
import groovyx.gpars.dataflow.operator.PoisonPill
import nextflow.Channel
import nextflow.file.FileHolder
import nextflow.script.EachInParam
import nextflow.script.FileSharedParam
import nextflow.script.SharedParam
/**
 * Defines the parallel tasks execution logic
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */

@Slf4j
@InheritConstructors
class ParallelTaskProcessor extends TaskProcessor {

    /**
     * Keeps track of the task instance executed by the current thread
     */
    protected final ThreadLocal<TaskRun> currentTask = new ThreadLocal<>()

    @Override
    protected void createOperator() {

        def opInputs = new ArrayList(taskConfig.getInputs().getChannels())
        def opOutputs = new ArrayList(taskConfig.getOutputs().getChannels())

        // append the shared obj to the input list
        def allScalar = taskConfig.getInputs().allScalarInputs()
        def sharedCount = taskConfig.getInputs().count { it instanceof SharedParam }

        /*
         * check if there are some iterators declaration
         * the list holds the index in the list of all *inputs* for the {@code each} declaration
         */
        def iteratorIndexes = []
        taskConfig.getInputs().eachWithIndex { param, index ->
            if( param instanceof EachInParam ) {
                log.trace "Process ${name} > got each param: ${param.name} at index: ${index} -- ${param.dump()}"
                iteratorIndexes << index
            }
        }

        /*
         * When one (or more) {@code each} are declared as input, it is created an extra
         * operator which will receive the inputs from the channel (excepts the values over iterate)
         *
         * The operator will *expand* the received inputs, iterating over the user provided value and
         * forwarding the final values the the second *parallel* processor executing the user specified task
         */
        if( iteratorIndexes ) {
            log.debug "Creating *combiner* operator for each param(s) at index(es): ${iteratorIndexes}"

            final size = opInputs.size()
            // the script implementing the iterating process
            final forwarder = createForwardWrapper(size, iteratorIndexes)
            // the channel forwarding the data from the *iterator* process to the target task
            final linkingChannels = new ArrayList(size)
            size.times { linkingChannels[it] = new DataflowQueue() }

            // instantiate the iteration process
            def params = [inputs: opInputs, outputs: linkingChannels, maxForks: 1, listeners: [new IteratorProcessInterceptor(allScalarValues: allScalar)]]
            session.allProcessors << (processor = new DataflowOperator(group, params, forwarder).start())

            // set as next inputs the result channels of the iteration process
            opInputs = linkingChannels
        }


        /*
         * create a mock closure to trigger the operator
         */
        final wrapper = createCallbackWrapper( opInputs.size(), this.&invokeTask )

        /*
         * define the max forks attribute:
         * - by default the process execution is parallel using the poolSize value
         * - when there is at least one shared variable it is executed in serial mode (maxForks==1) to guarantee thread safe access
         * - otherwise use the value defined by the user via 'taskConfig'
         */
        def maxForks = session.poolSize
        if( sharedCount ) {
            log.debug "Process declares shared inputs -- Using thread safe mode (maxForks=1)"
            maxForks = 1
            blocking = true
        }
        else if( taskConfig.maxForks ) {
            maxForks = taskConfig.maxForks
            blocking = true
        }
        log.debug "Creating operator > $name -- maxForks: $maxForks"

        /*
         * finally create the operator
         */
        def params = [inputs: opInputs, outputs: opOutputs, maxForks: maxForks, listeners: [new TaskProcessorInterceptor()] ]
        session.allProcessors << (processor = new DataflowOperator(group, params, wrapper).start())

    }

    /**
     * Implements the closure which *combines* all the iteration
     *
     * @param numOfInputs Number of in/out channel
     * @param indexes The list of indexes which identify the position of iterators in the input channels
     * @return The closure implementing the iteration/forwarding logic
     */
    protected createForwardWrapper( int numOfInputs, List indexes ) {

        final args = []
        numOfInputs.times { args << "x$it" }

        /*
         * Explaining the following closure:
         *
         * - it has to be evaluated as a string since the number of input must much the number input channel
         *   that is known only at runtime
         *
         * - 'out' holds the list of all input values which need to be forwarded (bound) to output as many
         *   times are the items in the iteration list
         *
         * - the iteration list(s) is (are) passed like in the closure inputs like the other values,
         *   the *indexes* argument defines the which of them are the iteration lists
         *
         * - 'itr' holds the list of all iteration lists
         *
         * - using the groovy method a combination of all values is create (cartesian product)
         *   see http://groovy.codehaus.org/groovy-jdk/java/util/Collection.html#combinations()
         *
         * - the resulting values are replaced in the 'out' array of values and forwarded out
         *
         */

        final str =
            """
            { ${args.join(',')} ->
                def out = [ ${args.join(',')} ]
                def itr = [ ${indexes.collect { 'x'+it }.join(',')} ]
                def cmb = itr.combinations()
                for( entries in cmb ) {
                    def count = 0
                    n.times { i->
                        if( i in indexes ) { out[i] = entries[count++] }
                    }
                    bindAllOutputValues( *out )
                }
            }
            """

        final Binding binding = new Binding( indexes: indexes, n: numOfInputs )
        final result = (Closure)new GroovyShell(binding).evaluate (str)

        return result

    }


    /**
     * The processor execution body
     *
     * @param processor
     * @param values
     */
    final protected void invokeTask( def args ) {

        // create and initialize the task instance to be executed
        List params = args instanceof List ? args : [args]
        final task = setupTask(params)

        // -- call the closure and execute the script
        currentTask.set(task)

        // -- verify if exists a stored result for this case,
        //    if true skip the execution and return the stored data
        if( checkStoredOutput(task) )
            return

        def hash = createTaskHashKey(task)
        checkCachedOrLaunchTask(task, hash, resumable, TaskProcessor.RunType.SUBMIT)
    }


    /**
     *  Intercept dataflow process events
     */
    class TaskProcessorInterceptor extends DataflowEventAdapter {

        @Override
        public List<Object> beforeRun(final DataflowProcessor processor, final List<Object> messages) {
            log.trace "<${name}> Before run -- messages: ${messages}"
            // this counter increment must be here, otherwise it is not coherent
            state.update { StateObj it -> it.incSubmitted() }
            return messages;
        }


        @Override
        void afterRun(DataflowProcessor processor, List<Object> messages) {
            log.trace "<${name}> After run"
            currentTask.remove()
        }

        @Override
        public Object messageArrived(final DataflowProcessor processor, final DataflowReadChannel<Object> channel, final int index, final Object message) {
            if( log.isTraceEnabled() ) {
                def channelName = taskConfig.getInputs()?.names?.get(index)
                def taskName = currentTask.get()?.name ?: name
                log.trace "<${taskName}> Message arrived -- ${channelName} => ${message}"
            }

            return message;
        }

        @Override
        public Object controlMessageArrived(final DataflowProcessor processor, final DataflowReadChannel<Object> channel, final int index, final Object message) {
            if( log.isTraceEnabled() ) {
                def channelName = taskConfig.getInputs()?.names?.get(index)
                def taskName = currentTask.get()?.name ?: name
                log.trace "<${taskName}> Control message arrived ${channelName} => ${message}"
            }

            if( message == PoisonPill.instance ) {
                log.debug "<${name}> Poison pill arrived"
                state.update { StateObj it -> it.poison() }

                // this control message avoid to stop the operator and
                // propagate the PoisonPill to the downstream processes
                return StopQuietly.instance
            }
            else {
                return message;
            }
        }

        @Override
        public void afterStop(final DataflowProcessor processor) {
            log.debug "<${name}> After stop -- shareObjs ${ParallelTaskProcessor.this.sharedObjs}"

            // bind shared outputs
            ParallelTaskProcessor.this.sharedObjs?.each { param, obj ->

                if( !param.outChannel )
                    return

                log.trace "Binding shared out param: ${param.name} = ${obj}"
                if( param instanceof FileSharedParam ) {
                    if( obj instanceof Collection )
                        obj = obj[0]
                    if( obj instanceof FileHolder )
                        obj = obj.storePath
                }

                param.outChannel.bind( obj )
            }
        }

        /**
         * Invoked if an exception occurs. Unless overridden by subclasses this implementation returns true to terminate the operator.
         * If any of the listeners returns true, the operator will terminate.
         * Exceptions outside of the operator's body or listeners' messageSentOut() handlers will terminate the operator irrespective of the listeners' votes.
         * When using maxForks, the method may be invoked from threads running the forks.
         * @param processor
         * @param error
         * @return
         */
        public boolean onException(final DataflowProcessor processor, final Throwable error) {
            handleException( error, currentTask.get() )
        }

    }

    /*
     * logger class for the *iterator* processor
     */
    class IteratorProcessInterceptor extends DataflowEventAdapter {

        boolean allScalarValues

        @Override
        public boolean onException(final DataflowProcessor processor, final Throwable e) {
            log.error "* process '$name' > error on internal iteration process", e
            return true;
        }

        @Override
        public Object messageArrived(final DataflowProcessor processor, final DataflowReadChannel<Object> channel, final int index, final Object message) {
            log.trace "* process '$name' > message arrived for iterator '${taskConfig.getInputs().names[index]}' with value: '$message'"
            return message;
        }

        @Override
        public Object messageSentOut(final DataflowProcessor processor, final DataflowWriteChannel<Object> channel, final int index, final Object message) {
            log.trace "* process '$name' > message forwarded for iterator '${taskConfig.getInputs().names[index]}' with value: '$message'"
            return message;
        }


        @Override
        public Object controlMessageArrived(final DataflowProcessor processor, final DataflowReadChannel<Object> channel, final int index, final Object message) {
            log.trace "* process '$name' > control message arrived for iterator '${taskConfig.getInputs().names[index]}'"
            return message;
        }

        @Override
        public void afterRun(final DataflowProcessor processor, final List<Object> messages) {
            log.trace "* process '$name' > after run on internal iteration process -- allScalarValues: $allScalarValues"
            if( allScalarValues )
                processor.terminate()
        }

        @Override
        public void afterStop(final DataflowProcessor processor) {
            log.trace "* process '$name' > after stop on internal iteration process -- allScalarValues: $allScalarValues"
            if( allScalarValues )
                processor.bindAllOutputs(Channel.STOP)
        }
    }


}
