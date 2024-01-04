/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.common.merkle.copy;

import static com.swirlds.common.merkle.copy.MerkleCopy.adoptChildren;
import static com.swirlds.common.merkle.copy.MerkleCopy.copyAnyNodeType;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.MerkleRouteException;
import com.swirlds.common.merkle.route.MerkleRoute;
import java.util.Iterator;

/**
 * A collection of utility methods for replacing paths within a merkle tree.
 */
public final class MerklePathReplacement {

    /**
     * The position of the child (i.e. the last node) within a path generated by
     * {@link #replacePath(MerkleNode, MerkleRoute)} relative to the length of the path.
     * To get the index of the child, add this value to the length of the path.
     */
    private static final int CHILD_PATH_OFFSET = -1;

    /**
     * The position of the parent within a path generated by {@link #replacePath(MerkleNode, MerkleRoute)}
     * relative to the length of the path. To get the index of the parent, add this value to the length
     * of the path.
     */
    private static final int PARENT_PATH_OFFSET = -2;

    /**
     * The position of the grandparent within a path generated by {@link #replacePath(MerkleNode, MerkleRoute)}
     * relative to the length of the path. To get the index of the grandparent, add this value to the length
     * of the path.
     */
    private static final int GRANDPARENT_PATH_OFFSET = -3;

    private MerklePathReplacement() {}

    /**
     * Make a copy of a node in the path, if needed. Does not copy if the child has a reference count that does not
     * exceed 1.
     *
     * @param child
     * 		the node to possibly copy
     * @param pathIndex
     * 		the position of the child within the path
     * @param pathLength
     * 		the total length of the path
     * @param skipAtEnd
     * 		the number of nodes at the end of the path that should not be copied
     * @param artificialChildReferences
     * 		the number of artificial references to the child. Only values of 0 or 1 are expected.
     * @param parentInPath
     * 		the parent that will hold the node after the copy
     * @param indexToCopy
     * 		the index within parentInPath
     * @param previousNodeCopied
     * 		true if a previous node in the path has already been copied
     * @return the copy if copied, otherwise the original node
     */
    private static MerkleNode copyChildIfNeeded(
            final MerkleNode child,
            final int pathIndex,
            final int pathLength,
            final int skipAtEnd,
            final int artificialChildReferences,
            final MerkleInternal parentInPath,
            final int indexToCopy,
            final boolean previousNodeCopied) {

        // Some of the nodes at the end of the path may be configured to be skipped.
        if (pathIndex >= pathLength - skipAtEnd) {
            return child;
        }

        MerkleNode childInPath = child;

        // We must copy if a previous node has been copied or if
        // the node has a reference count greater than 1 (excluding artificial references).
        if (previousNodeCopied || child.getReservationCount() > 1 + artificialChildReferences) {
            childInPath = copyAnyNodeType(child);

            // Add the copied child to the parent
            parentInPath.setChild(indexToCopy, childInPath, child.getRoute(), false);

            if (child.isInternal()) {
                // Add the original child's children to the new copy
                adoptChildren(child.asInternal(), childInPath.asInternal());
            }

        } else {
            // A node that doesn't get replaced must have its hash invalidated.
            childInPath.invalidateHash();
        }

        return childInPath;
    }

    /**
     * Utility method for skipping over steps within a route.
     *
     * @param iterator
     * 		an iterator over the steps in a route
     * @param stepsToSkip
     * 		the number of steps to skip
     */
    private static void skipStepsInRoute(final Iterator<Integer> iterator, final int stepsToSkip) {
        for (int i = 0; i < stepsToSkip; i++) {
            if (!iterator.hasNext()) {
                throw new IllegalStateException("can not skip more steps there exist within the iterator");
            }
            iterator.next();
        }
    }

    /**
     * Utility method. Adds a node to the path array.
     *
     * @param path
     * 		an array where a path is being constructed
     * @param nodesRequiringInitialization
     * 		an array containing data about which nodes require initialization
     * @param pathIndex
     * 		the index of the node within the path
     * @param node
     * 		the node to add to the path
     * @param nodeWasCopied
     * 		true if this node was copied
     */
    private static void addNodeToPath(
            final MerkleNode[] path,
            final boolean[] nodesRequiringInitialization,
            final int pathIndex,
            final MerkleNode node,
            final boolean nodeWasCopied) {
        path[pathIndex] = node;
        nodesRequiringInitialization[pathIndex] = nodeWasCopied;
    }

    /**
     * Utility method. Take an artificial reference to nodes to prevent them from being prematurely deleted.
     *
     * @param isLastNode
     * 		true if the child is the last node in the path
     * @param root
     * 		the root of the path
     * @param parent
     * 		the parent of the current child
     * @param child
     * 		the child of the current parent
     * @return the number of artificial references taken on the child
     */
    private static int handleArtificialReferences(
            final boolean isLastNode, final MerkleNode root, final MerkleNode parent, final MerkleNode child) {

        int artificialChildReferences = 0;
        if (!isLastNode) {
            // Increase the reference count of the original child.
            // Ensures it (and its descendants) don't get released when it is replaced by the copy
            child.reserve();
            artificialChildReferences++;
        }
        if (parent != root) {
            // Now that the child holds a reference count, release the artificial reference count on the parent
            parent.release();
        }
        return artificialChildReferences;
    }

    /**
     * Initialize nodes in the path that were need it.
     *
     * Note: this step will not be necessary once internal nodes have a uniform and well behaving copy().
     *
     * @param path
     * 		nodes in the path
     * @param nodeRequiringInitialization
     * 		a list of boolean values, true if the node needs initialization
     */
    private static void initializePath(final MerkleNode[] path, final boolean[] nodeRequiringInitialization) {
        // Children must always be initialized before their parents, iterate backwards.
        for (int index = path.length - 1; index >= 0; index--) {
            if (nodeRequiringInitialization[index]) {
                MerkleNode node = path[index];
                if (!node.isLeaf()) {
                    node.asInternal().rebuild();
                }
            }
        }
    }

    /**
     * Replace a path from the root down to a given node. It is assumed that the root never needs to be replaced.
     *
     * WARNING: until merkle nodes become naturally fast copyable, it is assumed that each node in the path that needs
     * to be replaced is fast copyable and that each fast copy operation does not attempt to copy its children.
     *
     * @param root
     * 		the root of the tree (or subtree). Is not replaced.
     * @param route
     * 		the route to replace
     * @return all nodes in the replaced path
     */
    public static MerkleNode[] replacePath(final MerkleNode root, final MerkleRoute route) {
        return replacePath(root, route, 0);
    }

    /**
     * Replace a path from one node down to another node, skipping the last several steps in the route
     * if configured. It is assumed that the first node in the path never needs to be replaced.
     *
     * Nodes in the newly created path are initialized via the {@link MerkleInternal#rebuild()}
     * method.
     *
     * All nodes along the given path are returned with null hashes, with the exception of the skipped nodes
     * at the end of the path (if there are any) which are returned without the hashes being modified.
     *
     * @param firstNodeInPath
     * 		the first node in the path. Is not replaced.
     * @param route
     * 		the route to replace
     * @param skipAtEnd
     * 		the number of steps at the end of the path to NOT copy. If 0 then the entire path is replaced.
     * 		If 1 then the entire path with the exception of the last node is replaced, and so on.
     * 		Nodes not replaced are still included in the returned path.
     * @return all nodes in the replaced path, followed by nodes along the route that were not replaced
     */
    public static MerkleNode[] replacePath(
            final MerkleNode firstNodeInPath, final MerkleRoute route, final int skipAtEnd) {

        if (firstNodeInPath == null) {
            throw new IllegalArgumentException("Can not replace path in null tree");
        }

        final int firstNodeDepth = firstNodeInPath.getRoute().size();
        final int pathLength = route.size() - firstNodeDepth + 1;

        // This iterator will be used to travel down the path described by the route.
        final Iterator<Integer> iterator = route.iterator();

        // Skip steps from true root down to root of replacement path.
        skipStepsInRoute(iterator, firstNodeDepth);

        // Contains each node in the path, including both unreplaced nodes and replaced nodes.
        MerkleNode[] path = new MerkleNode[pathLength];

        // Keep track of which nodes require initialization.
        boolean[] nodesRequiringInitialization = new boolean[pathLength];

        // The index of the next node being inserted into the path.
        int pathIndex = 0;

        // The path includes the first node, even though it is never replaced.
        addNodeToPath(path, nodesRequiringInitialization, pathIndex, firstNodeInPath, false);
        pathIndex++;

        if (firstNodeInPath.isLeaf()) {
            if (pathLength != 1) {
                throw new MerkleRouteException("First node is a leaf but path has length " + pathLength);
            }
            return path;
        }

        MerkleInternal parent = firstNodeInPath.cast();
        MerkleInternal parentInPath = parent;

        boolean previousNodeCopied = false;
        while (iterator.hasNext()) {
            int indexToCopy = iterator.next();
            final MerkleNode child = parent.getChild(indexToCopy);

            final int artificialChildReferences =
                    handleArtificialReferences(!iterator.hasNext(), firstNodeInPath, parent, child);

            MerkleNode childInPath = copyChildIfNeeded(
                    child,
                    pathIndex,
                    pathLength,
                    skipAtEnd,
                    artificialChildReferences,
                    parentInPath,
                    indexToCopy,
                    previousNodeCopied);

            final boolean nodeWasCopied = child != childInPath;
            previousNodeCopied |= nodeWasCopied;
            addNodeToPath(path, nodesRequiringInitialization, pathIndex, childInPath, nodeWasCopied);
            pathIndex++;

            if (iterator.hasNext()) {
                parent = child.cast();
                parentInPath = childInPath.cast();
            }
        }

        initializePath(path, nodesRequiringInitialization);

        if (pathIndex != pathLength) {
            throw new MerkleRouteException(
                    "Path expected to be of length " + pathLength + " but is actually of length " + pathIndex);
        }

        return path;
    }

    /**
     * Get the child in a given path generated by {@link #replacePath(MerkleNode, MerkleRoute)}. The child
     * is considered to be the last node in the path.
     *
     * @param path
     * 		a path
     * @param <T>
     * 		the type of data returned
     * @return the child (i.e. the last node in the path)
     */
    public static <T extends MerkleNode> T getChildInPath(final MerkleNode[] path) {
        return path[path.length + CHILD_PATH_OFFSET].cast();
    }

    /**
     * Get the parent in a given path generated by {@link #replacePath(MerkleNode, MerkleRoute)}. The parent
     * is considered to be the second to last node in the path.
     *
     * @param path
     * 		a path
     * @param <T>
     * 		the type of data returned
     * @return the parent (i.e. the second to last node in the path)
     */
    public static <T extends MerkleInternal> T getParentInPath(final MerkleNode[] path) {
        return path[path.length + PARENT_PATH_OFFSET].cast();
    }

    /**
     * Get the grandparent in a given path generated by {@link #replacePath(MerkleNode, MerkleRoute)}. The grandparent
     * is considered to be the third to last node in the path.
     *
     * @param path
     * 		a path
     * @param <T>
     * 		the type of data returned
     * @return the grandparent (i.e. the third to last node in the path)
     */
    public static <T extends MerkleInternal> T getGrandparentInPath(final MerkleNode[] path) {
        return path[path.length + GRANDPARENT_PATH_OFFSET].cast();
    }
}
