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

import static groovyx.gpars.dataflow.Dataflow.task

import groovyx.gpars.dataflow.Dataflow
import groovyx.gpars.dataflow.DataflowQueue
import groovyx.gpars.dataflow.operator.DataflowOperator


/**
 * Shows how to build operators using the ProcessingNode class
 */

aValues = new DataflowQueue()
bValues = new DataflowQueue()
results = new DataflowQueue()


def callback = { println 'ciao' }

def str = '{ x, y -> callback(); bindOutput x + y }'
def closure = new GroovyShell(new Binding(callback:callback)).evaluate(str)

def params = [inputs: [aValues,bValues], outputs: [results], maxForks: 1]
new DataflowOperator(Dataflow.DATA_FLOW_GROUP, params, closure).start()


//Now the operator is running and processing the data
task {

    int count=0
    while(true) {
        aValues << (++count) * 10
        sleep 500
    }

}

task {

    int count=0
    while(true) {
        bValues << (++count)
        sleep 700
    }

}


while(true) println results.val
