/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.drools.core.reteoo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.drools.core.RuleBaseConfiguration;
import org.drools.core.base.ObjectType;
import org.drools.core.common.BaseNode;
import org.drools.core.common.ReteEvaluator;
import org.drools.core.common.RuleBasePartitionId;
import org.drools.core.common.UpdateContext;
import org.drools.core.definitions.rule.impl.RuleImpl;
import org.drools.core.reteoo.SegmentMemory.SegmentPrototype;
import org.drools.core.reteoo.builder.BuildContext;
import org.drools.core.rule.Declaration;
import org.drools.core.rule.GroupElement;
import org.drools.core.rule.Pattern;
import org.drools.core.util.bitmask.AllSetBitMask;
import org.drools.core.util.bitmask.BitMask;
import org.drools.core.util.bitmask.EmptyBitMask;

import static org.drools.core.reteoo.PropertySpecificUtil.isPropertyReactive;

public abstract class AbstractTerminalNode extends BaseNode implements TerminalNode {
    /** The rule to invoke upon match. */
    private RuleImpl                      rule;

    /**
     * the subrule reference is needed to resolve declarations
     * because declarations may have different offsets in each subrule
     */
    private GroupElement      subrule;
    private int               subruleIndex;
    private Declaration[]     allDeclarations;
    protected Declaration[]   requiredDeclarations;


    private LeftTupleSinkNode previousTupleSinkNode;
    private LeftTupleSinkNode nextTupleSinkNode;

    private LeftTupleSource   tupleSource;

    private BitMask declaredMask = EmptyBitMask.get();
    private BitMask inferredMask = EmptyBitMask.get();
    private BitMask negativeMask = EmptyBitMask.get();

    private LeftTupleNode[]         pathNodes;

    private transient PathEndNode[] pathEndNodes;

    private SegmentPrototype[]      segmentPrototypes;

    private SegmentPrototype[]      eagerSegmentPrototypes;

    protected PathMemSpec           pathMemSpec;

    private int                     objectCount;

    public AbstractTerminalNode() { }

    public AbstractTerminalNode(int id, RuleBasePartitionId partitionId, boolean partitionsEnabled, LeftTupleSource source,
                                BuildContext context,
                                RuleImpl rule, GroupElement subrule, int subruleIndex) {
        super(id, partitionId, partitionsEnabled);
        this.tupleSource = source;
        this.rule = rule;
        this.subrule = subrule;
        this.subruleIndex = subruleIndex;
        this.setObjectCount(getLeftTupleSource().getObjectCount()); // 'terminal' nodes do not increase the count
        context.addPathEndNode(this);
        initMemoryId( context );
        initDeclaredMask(context);
        initInferredMask();

        Map<String, Declaration> decls = this.subrule.getOuterDeclarations();
        this.allDeclarations = decls.values().toArray( new Declaration[decls.size()] );
        initDeclarations(decls, context);
    }

    abstract void initDeclarations(Map<String, Declaration> decls, BuildContext context);

    @Override
    public PathMemSpec getPathMemSpec() {
        return getPathMemSpec(null);
    }

    @Override
    public PathMemSpec getPathMemSpec(TerminalNode removingTN) {
        if (pathMemSpec == null) {
            pathMemSpec = calculatePathMemSpec( null, removingTN );
        }
        return pathMemSpec;
    }

    @Override
    public void resetPathMemSpec(TerminalNode removingTN) {
        // null all PathMemSpecs, for all pathEnds, or the recursion will use a previous value.
        // calling calculatePathMemSpec, will eventually getPathMemSpec on all nested rians, so previous values must be nulled
        Arrays.stream(pathEndNodes).forEach( n -> {
            n.nullPathMemSpec();
            n.setSegmentPrototypes(null);
            n.setEagerSegmentPrototypes(null);}
        );
        pathMemSpec = removingTN == null ? null : calculatePathMemSpec( null, removingTN );
    }

    public RuleImpl getRule() {
        return this.rule;
    }

    public GroupElement getSubRule() {
        return this.subrule;
    }

    @Override
    public int getSubruleIndex() {
        return subruleIndex;
    }

    public Declaration[] getAllDeclarations() {
        return this.allDeclarations;
    }

    public Declaration[] getRequiredDeclarations() {
        return this.requiredDeclarations;
    }

    public void nullPathMemSpec() {
        pathMemSpec = null;
    }

    @Override
    public void setPathEndNodes(PathEndNode[] pathEndNodes) {
        this.pathEndNodes = pathEndNodes;
    }


    @Override
    public PathEndNode[] getPathEndNodes() {
        return pathEndNodes;
    }

    @Override
    public void setSegmentPrototypes(SegmentPrototype[] smems) {
        this.segmentPrototypes = smems;
    }

    @Override
    public SegmentPrototype[] getSegmentPrototypes() {
        return segmentPrototypes;
    }

    public SegmentPrototype[] getEagerSegmentPrototypes() {
        return eagerSegmentPrototypes;
    }

    public void setEagerSegmentPrototypes(SegmentPrototype[] eagerSegmentPrototypes) {
        this.eagerSegmentPrototypes = eagerSegmentPrototypes;
    }

    public int getPathIndex() {
        return tupleSource.getPathIndex() + 1;
    }

    public int getObjectCount() {
        return objectCount;
    }

    public void setObjectCount(int count) {
        objectCount = count;
    }

    protected void initDeclaredMask(BuildContext context) {
        if ( !(unwrapTupleSource() instanceof LeftInputAdapterNode)) {
            // RTN's not after LIANode are not relevant for property specific, so don't block anything.
            setDeclaredMask( AllSetBitMask.get() );
            return;
        }

        Pattern pattern = context.getLastBuiltPatterns()[0];
        ObjectType objectType = pattern.getObjectType();

        if ( isPropertyReactive(context, objectType) ) {
            List<String> accessibleProperties = pattern.getAccessibleProperties( context.getRuleBase() );
            setDeclaredMask( pattern.getPositiveWatchMask(accessibleProperties) );
            setNegativeMask( pattern.getNegativeWatchMask(accessibleProperties) );
        } else  {
            // if property specific is not on, then accept all modification propagations
            setDeclaredMask( AllSetBitMask.get() );
        }
    }

    public void initInferredMask() {
        LeftTupleSource leftTupleSource = unwrapTupleSource();
        if ( leftTupleSource instanceof LeftInputAdapterNode && ((LeftInputAdapterNode)leftTupleSource).getParentObjectSource() instanceof AlphaNode ) {
            AlphaNode alphaNode = (AlphaNode) ((LeftInputAdapterNode)leftTupleSource).getParentObjectSource();
            setInferredMask( alphaNode.updateMask( getDeclaredMask() ) );
        } else {
            setInferredMask(  getDeclaredMask() );
        }

        setInferredMask( getInferredMask().resetAll( getNegativeMask() ) );
        if ( getNegativeMask().isAllSet() && !getDeclaredMask().isAllSet() ) {
            setInferredMask( getInferredMask().setAll( getDeclaredMask() ) );
        }
    }

    public LeftTupleSource unwrapTupleSource() {
        return tupleSource instanceof FromNode ? tupleSource.getLeftTupleSource() : tupleSource;
    }

    public PathMemory createMemory(RuleBaseConfiguration config, ReteEvaluator reteEvaluator) {
        return initPathMemory( this, new PathMemory(this, reteEvaluator) );
    }

    public static PathMemory initPathMemory( PathEndNode pathEndNode, PathMemory pmem ) {
        PathMemSpec pathMemSpec = pathEndNode.getPathMemSpec();
        pmem.setAllLinkedMaskTest(pathMemSpec.allLinkedTestMask );
        pmem.setSegmentMemories( new SegmentMemory[pathEndNode.getPathMemSpec().smemCount()] ); // this must initially be null, so it can be used for the initialised check.
        return pmem;
    }

    public LeftTuple createPeer(LeftTuple original) {
        RuleTerminalNodeLeftTuple peer = (RuleTerminalNodeLeftTuple) AgendaComponentFactory.get().createTerminalTuple();
        peer.initPeer( (BaseLeftTuple) original, this );
        original.setPeer( peer );
        return peer;
    }

    protected boolean doRemove(final RuleRemovalContext context,
                               final ReteooBuilder builder) {
        getLeftTupleSource().removeTupleSink(this);
        this.tupleSource = null;
        return true;
    }

    public LeftTupleSource getLeftTupleSource() {
        return this.tupleSource;
    }

    public BitMask getDeclaredMask() {
        return declaredMask;
    }

    public BitMask getInferredMask() {
        return inferredMask;
    }
    
    public BitMask getLeftInferredMask() {
        return inferredMask;
    }

    public void setDeclaredMask(BitMask mask) {
        declaredMask = mask;
    }

    public void setInferredMask(BitMask mask) {
        inferredMask = mask;
    }

    public BitMask getNegativeMask() {
        return negativeMask;
    }

    public void setNegativeMask(BitMask mask) {
        negativeMask = mask;
    }

    public void networkUpdated(UpdateContext updateContext) {
        getLeftTupleSource().networkUpdated(updateContext);
    }

    public boolean isInUse() {
        return false;
    }

    public boolean isLeftTupleMemoryEnabled() {
        return false;
    }

    public static LeftTupleNode[] getPathNodes(PathEndNode endNode) {
        LeftTupleNode[] pathNodes = new LeftTupleNode[endNode.getPathIndex() + 1];
        for (LeftTupleNode node = endNode; node != null; node = node.getLeftTupleSource()) {
            pathNodes[node.getPathIndex()] = node;
        }
        return pathNodes;
    }

    public LeftTupleNode[] getPathNodes() {
        if (pathNodes == null) {
            pathNodes = getPathNodes( this );
        }
        return pathNodes;
    }

    public final boolean hasPathNode(LeftTupleNode node) {
        for (LeftTupleNode pathNode : getPathNodes()) {
            if (node.getId() == pathNode.getId()) {
                return true;
            }
        }
        return false;
    }

    public final boolean isTerminalNodeOf(LeftTupleNode node) {
        for (PathEndNode pathEndNode : getPathEndNodes()) {
            if (pathEndNode.hasPathNode( node )) {
                return true;
            }
        }
        return false;
    }

    public LeftTupleSinkPropagator getSinkPropagator() {
        return EmptyLeftTupleSinkAdapter.getInstance();
    }

    @Override
    public final void setPartitionIdWithSinks( RuleBasePartitionId partitionId ) {
        this.partitionId = partitionId;
    }

    @Override
    public ObjectTypeNode getObjectTypeNode() {
        return getLeftTupleSource().getObjectTypeNode();
    }


    /**
     * Returns the next node
     * @return
     *      The next TupleSinkNode
     */
    public LeftTupleSinkNode getNextLeftTupleSinkNode() {
        return this.nextTupleSinkNode;
    }

    /**
     * Sets the next node
     * @param next
     *      The next TupleSinkNode
     */
    public void setNextLeftTupleSinkNode(final LeftTupleSinkNode next) {
        this.nextTupleSinkNode = next;
    }

    /**
     * Returns the previous node
     * @return
     *      The previous TupleSinkNode
     */
    public LeftTupleSinkNode getPreviousLeftTupleSinkNode() {
        return this.previousTupleSinkNode;
    }

    /**
     * Sets the previous node
     * @param previous
     *      The previous TupleSinkNode
     */
    public void setPreviousLeftTupleSinkNode(final LeftTupleSinkNode previous) {
        this.previousTupleSinkNode = previous;
    }

    public void visitLeftTupleNodes(Consumer<LeftTupleNode> func) {
        for (PathEndNode endNode : getPathEndNodes()) {
            for (LeftTupleNode node : endNode.getPathNodes()) {
                func.accept(node);
            }
        }
    }

    protected int calculateHashCode() {
        return (31 * (31 + this.rule.hashCode() )) + subruleIndex;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) {
            return true;
        }

        if ( object == null || !(object instanceof RuleTerminalNode) || this.hashCode() != object.hashCode() ) {
            return false;
        }
        final TerminalNode other = (TerminalNode) object;
        return getRule().equals(other.getRule()) && getSubruleIndex() == other.getSubruleIndex();
    }

}
