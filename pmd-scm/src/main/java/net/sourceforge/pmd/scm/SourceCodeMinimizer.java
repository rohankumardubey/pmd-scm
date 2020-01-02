/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.pmd.lang.Parser;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.scm.invariants.Invariant;
import net.sourceforge.pmd.scm.invariants.InvariantOperations;
import net.sourceforge.pmd.scm.strategies.MinimizationStrategy;
import net.sourceforge.pmd.scm.strategies.MinimizerOperations;

public class SourceCodeMinimizer implements InvariantOperations, MinimizerOperations {
    private static final class ContinueException extends Exception { }

    private static final class ExitException extends Exception { }

    private final MinimizerLanguage language;
    private final Invariant invariant;
    private final MinimizationStrategy strategy;
    private final List<ASTCutter> cutters;
    private List<Node> currentRoots;

    public SourceCodeMinimizer(SCMConfiguration configuration) throws IOException {
        language = configuration.getLanguageHandler();
        Parser parser = language.getParser(configuration.getLanguageVersion());
        invariant = configuration.getInvariantCheckerConfig().createChecker();
        strategy = configuration.getStrategyConfig().createStrategy();

        Charset sourceCharset = configuration.getSourceCharset();
        cutters = new ArrayList<>();
        for (SCMConfiguration.FileMapping mapping: configuration.getFileMappings()) {
            Files.copy(mapping.input, mapping.output, StandardCopyOption.REPLACE_EXISTING);
            ASTCutter cutter = new ASTCutter(parser, sourceCharset, mapping.output);
            cutters.add(cutter);
        }
        currentRoots = ASTCutter.commitAll(cutters);
    }

    @Override
    public boolean allInputsAreParseable() throws IOException {
        for (ASTCutter cutter: cutters) {
            if (!cutter.isScratchFileParseable()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public NodeInformationProvider getNodeInformationProvider() {
        return language.getNodeInformationProvider();
    }

    /**
     * Check invariant and commit if succesful.
     *
     * @param throwOnSuccess If successfully committed, unwind stack with {@link ContinueException}
     * @return <code>false</code> if unsuccessful, <code>true</code> if successful and <code>throwOnSuccess == false</code>
     * @throws ContinueException If successful and <code>throwOnSuccess == true</code>
     */
    private boolean tryCommit(boolean throwOnSuccess) throws Exception {
        if (!invariant.checkIsSatisfied()) {
            return false;
        }
        // now, invariant is satisfied
        List<Node> roots = ASTCutter.commitAll(cutters);
        if (roots == null) {
            return false;
        }
        currentRoots = roots;
        // and parsed OK, so unwinding...
        if (throwOnSuccess) {
            throw new ContinueException();
        }
        // ... or just returning
        return true;
    }

    @Override
    public void tryCleanup() throws Exception {
        tryCleanup(true);
    }

    public void tryCleanup(boolean throwOnSuccess) throws Exception {
        for (ASTCutter cutter: cutters) {
            cutter.writeCleanedUpSource();
        }
        tryCommit(throwOnSuccess);
    }

    @Override
    public void tryRemoveNodes(Collection<Node> nodesToRemove) throws Exception {
        Set<Node> nodes = new HashSet<>(nodesToRemove);
        for (ASTCutter cutter : cutters) {
            Set<Node> currentNodesToRemove = new HashSet<>(cutter.getAllNodes());
            currentNodesToRemove.retainAll(nodes);
            cutter.writeTrimmedSource(currentNodesToRemove);
            nodes.removeAll(currentNodesToRemove);
        }
        if (!nodes.isEmpty()) {
            System.err.println("WARNING: strategy tries to remove unknown nodes!");
        }
        tryCommit(true);
    }

    @Override
    public void forceRemoveNodesAndExit(Collection<Node> nodesToRemove) throws Exception {
        for (ASTCutter cutter : cutters) {
            cutter.writeTrimmedSource(nodesToRemove);
            cutter.commitChange();
        }
        throw new ExitException();
    }

    private int getTotalFileSize() {
        int result = 0;
        for (ASTCutter cutter: cutters) {
            result += (int) cutter.getScratchFile().toFile().length();
        }
        return result;
    }

    private int getTotalNodeCount() {
        int result = 0;
        for (Node root : currentRoots) {
            result += getNodeCount(root);
        }
        return result;
    }

    private int getNodeCount(Node subtree) {
        int result = 1;
        for (int i = 0; i < subtree.jjtGetNumChildren(); ++i) {
            result += getNodeCount(subtree.jjtGetChild(i));
        }
        return result;
    }

    private void printStats(String when, int originalSize, int originalNodeCount) {
        int totalSize = getTotalFileSize();
        int totalNodeCount = getTotalNodeCount();
        int pcSize = totalSize * 100 / originalSize;
        int pcNodes = totalNodeCount * 100 / originalNodeCount;
        System.out.println(when + ": size "
                + totalSize + " bytes (" + pcSize + "%), "
                + totalNodeCount + " nodes (" + pcNodes + "%)");
        System.out.flush();
    }

    public void runMinimization() throws Exception {
        strategy.initialize(this);
        invariant.initialize(this);

        final int originalSize = getTotalFileSize();
        final int originalNodeCount = getTotalNodeCount();
        System.out.println("Original file(s): " + originalSize + " bytes, " + originalNodeCount + " nodes.");
        System.out.flush();

        tryCleanup(false);
        printStats("After initial white-space cleanup", originalSize, originalNodeCount);

        int passNumber = 0;
        boolean shouldContinue = true;
        while (shouldContinue) {
            passNumber += 1;
            boolean performCleanup = passNumber % 10 == 0;
            try {
                if (performCleanup) {
                    tryCleanup();
                } else {
                    strategy.performSinglePass(currentRoots);
                    shouldContinue = false;
                }
            } catch (ContinueException ex) {
                shouldContinue = true;
            } catch (ExitException ex) {
                shouldContinue = false;
            }

            String cleanupLabel = performCleanup ? " (white-space cleanup)" : "";

            printStats("After pass #" + passNumber + cleanupLabel, originalSize, originalNodeCount);
        }

        tryCleanup(false);
        printStats("After final white-space cleanup", originalSize, originalNodeCount);
        for (ASTCutter cutter : cutters) {
            cutter.writeWithoutEmptyLines();
            tryCommit(false);
        }
        printStats("After blank line clean up", originalSize, originalNodeCount);

        for (ASTCutter cutter : cutters) {
            cutter.rollbackChange(); // to the last committed state
            cutter.close();
        }

        invariant.printStatistics(System.out);
        strategy.printStatistics(System.out);
    }
}
