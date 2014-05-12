/*
 * Copyright (c) 2013, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package edu.uci.python.parser;

import java.util.*;

import org.python.google.common.primitives.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.nodes.*;

import edu.uci.python.nodes.*;
import edu.uci.python.nodes.control.*;
import edu.uci.python.nodes.frame.*;
import edu.uci.python.nodes.function.*;
import edu.uci.python.nodes.generator.*;
import edu.uci.python.nodes.statement.*;
import edu.uci.python.runtime.*;

public class GeneratorTranslator {

    private final FunctionRootNode root;
    private final PythonContext context;
    private int numOfGeneratorBlockNode;
    private int numOfGeneratorForNode;

    public GeneratorTranslator(PythonContext context, FunctionRootNode root) {
        this.context = context;
        this.root = root;
    }

    public RootCallTarget translate() {
        RootCallTarget callTarget = Truffle.getRuntime().createCallTarget(root);
        /**
         * Replace {@link ReturnTargetNode}.
         */
        List<ReturnTargetNode> returnTargets = NodeUtil.findAllNodeInstances(root, ReturnTargetNode.class);
        assert returnTargets.size() == 1;
        splitArgumentLoads(returnTargets.get(0));

        /**
         * Redirect local variable accesses to materialized persistent frame.
         */
        for (WriteLocalVariableNode write : NodeUtil.findAllNodeInstances(root, WriteLocalVariableNode.class)) {
            write.replace(WriteGeneratorFrameVariableNodeFactory.create(write.getSlot(), write.getRhs()));
        }

        for (ReadLocalVariableNode read : NodeUtil.findAllNodeInstances(root, ReadLocalVariableNode.class)) {
            read.replace(ReadGeneratorFrameVariableNode.create(read.getSlot()));
        }

        /**
         * For some weird reason, some reads are not replaced. Have to go through all reads and make
         * sure they are replaced.
         */
        for (ReadLocalVariableNode read : NodeUtil.findAllNodeInstances(root, ReadLocalVariableNode.class)) {
            read.replace(ReadGeneratorFrameVariableNode.create(read.getSlot()));
        }

        assert NodeUtil.findFirstNodeInstance(root, ReadLocalVariableNode.class) == null;

        for (YieldNode yield : NodeUtil.findAllNodeInstances(root, YieldNode.class)) {
            int depth = 0;
            PNode current = yield;

            while (current.getParent() != root) {
                current = (PNode) current.getParent();
                replaceControls(current, yield, depth++);
            }
        }

        for (GeneratorExpressionNode genexp : NodeUtil.findAllNodeInstances(root, GeneratorExpressionNode.class)) {
            genexp.setEnclosingFrameGenerator(true);
            NodeUtil.findMatchingNodeIn(genexp, root.getUninitializedBody()).setEnclosingFrameGenerator(true);
        }

        for (BreakNode bnode : NodeUtil.findAllNodeInstances(root, BreakNode.class)) {
            // look for it's breaking loop node
            Node current = bnode.getParent();
            List<Integer> indexSlots = new ArrayList<>();
            while (current instanceof GeneratorBlockNode || current instanceof ContinueTargetNode || current instanceof IfNode) {
                if (current instanceof GeneratorBlockNode) {
                    int indexSlot = ((GeneratorBlockNode) current).getIndexSlot();
                    indexSlots.add(indexSlot);
                }
                current = current.getParent();
            }

            if (current instanceof GeneratorForNode) {
                int iteratorSlot = ((GeneratorForNode) current).getIteratorSlot();
                int[] indexSlotsArray = Ints.toArray(indexSlots);
                bnode.replace(new BreakNode.GeneratorBreakNode(iteratorSlot, indexSlotsArray));
            }
        }

        return callTarget;
    }

    private void splitArgumentLoads(ReturnTargetNode returnTarget) {
        assert context != null;

        if (returnTarget.getBody() instanceof BlockNode) {
            BlockNode body = (BlockNode) returnTarget.getBody();
            assert body.getStatements().length == 2;
            PNode argumentLoads = body.getStatements()[0];
            returnTarget.replace(new GeneratorReturnTargetNode(argumentLoads, body.getStatements()[1], returnTarget.getReturn()));
        } else {
            returnTarget.replace(new GeneratorReturnTargetNode(EmptyNode.INSTANCE, returnTarget.getBody(), returnTarget.getReturn()));
        }
    }

    private void replaceControls(PNode node, YieldNode yield, int depth) {
        /**
         * Has it been replace already?
         */
        if (node.getClass().getSimpleName().contains("Generator")) {
            return;
        }

        if (node instanceof ForWithLocalTargetNode) {
            ForWithLocalTargetNode forNode = (ForWithLocalTargetNode) node;
            AdvanceIteratorNode next = (AdvanceIteratorNode) forNode.getTarget();
            WriteGeneratorFrameVariableNode target = (WriteGeneratorFrameVariableNode) next.getTarget();
            GetIteratorNode getIter = (GetIteratorNode) forNode.getIterator();
            int iteratorSlot = nextGeneratorForNodeSlot();

            if (depth == 0) {
                node.replace(new GeneratorForNode.InnerGeneratorForNode(target, getIter, forNode.getBody(), iteratorSlot));
            } else {
                node.replace(new GeneratorForNode(target, getIter, forNode.getBody(), iteratorSlot));
            }
        } else if (node instanceof BlockNode) {
            BlockNode block = (BlockNode) node;
            int slotOfBlockIndex = nextGeneratorBlockIndexSlot();

            if (yield.getParent().equals(block)) {
                yield.replace(new YieldNode.GeneratorYieldNode(yield.getRhs(), slotOfBlockIndex));
            }

            node.replace(new GeneratorBlockNode(block.getStatements(), slotOfBlockIndex));
        } else if (node instanceof IfNode || node instanceof ElseNode || node instanceof BreakTargetNode || node instanceof WhileNode) {
            // do nothing for now
        } else {
            TranslationUtil.notCovered();
        }
    }

    private int nextGeneratorBlockIndexSlot() {
        return numOfGeneratorBlockNode++;
    }

    public int getNumOfGeneratorBlockNode() {
        return numOfGeneratorBlockNode;
    }

    private int nextGeneratorForNodeSlot() {
        return numOfGeneratorForNode++;
    }

    public int getNumOfGeneratorForNode() {
        return numOfGeneratorForNode;
    }

    public RootCallTarget createParallelGeneratorCallTarget() {
        if (!PythonOptions.ParallelizeGeneratorCalls) {
            return null;
        }

        PNode parallelBody = root.getClonedUninitializedBody();

        for (YieldNode yield : NodeUtil.findAllNodeInstances(parallelBody, YieldNode.class)) {
            yield.replace(ParallelYieldNode.create(yield.getRhs()));
        }

        for (GeneratorExpressionNode genexp : NodeUtil.findAllNodeInstances(parallelBody, GeneratorExpressionNode.class)) {
            genexp.setEnclosingFrameGenerator(false);
        }

        RootNode parallelRoot = new FunctionRootNode(context, root.getFunctionName(), root.getFrameDescriptor(), parallelBody);
        return Truffle.getRuntime().createCallTarget(parallelRoot);
    }

}
