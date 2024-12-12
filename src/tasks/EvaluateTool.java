package tasks;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Set;
import java.util.HashSet;

import cloneMatchingAlgorithms.CloneMatcher;
import cloneMatchingAlgorithms.CoverageMatcher;
import database.BigCloneBenchDB;
import database.Clones;
import database.Functionalities;
import database.Functionality;
import database.Tool;
import database.Tools;
import evaluate.ToolEvaluator;
import picocli.CommandLine;
import util.BigCloneEvalVersion;
import util.FixPath;

@CommandLine.Command(
        name = "evaluateTool",
        description = "Measures the recall of the specific tool based on the clones imported for it. " +
                "Highly configureable, including using custom clone-matching algorithms. " +
                "Summarizes recall per clone type, per inter vs intra-project clones, per functionality in BigCloneBench" +
                "and for different syntactical similarity regions in the output tool evaluation report.",
        mixinStandardHelpOptions = true,
        versionProvider = util.Version.class)
public class EvaluateTool implements Callable<Void> {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Mixin
    private MixinOptions.ToolId toolId;

    @CommandLine.Mixin
    private MixinOptions.EvaluationOptions options;

    @CommandLine.Mixin
    private MixinOptions.OutputFile output;

    @CommandLine.Option(
            names = {"-m", "--matcher"},
            description = "Specify the clone matcher. See documentation for configuration strings. "
                    + "Default is coverage-matcher with 70%% coverage threshold.",
            paramLabel = "<MATCHER>"
    )
    private String matcherSpec = "CoverageMatcher 0.7";

    public static void panic(int exitval, Throwable cause, String message) {
        if (message != null) System.err.println(message);
        if (cause != null) cause.printStackTrace(System.err);
        System.exit(exitval);
    }

    public static void main(String[] args) {
        new CommandLine(new EvaluateTool()).execute(args);
    }

    public Void call() throws SQLException, IOException, ClassNotFoundException, NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {
        Tool tool = Tools.getTool(toolId.id);
        if (tool == null) {
            panic(-1, null, "There is no such tool with ID " + toolId.id + ".");
            return null;
        }

        CloneMatcher matcher;
        String[] parts = matcherSpec.split("\\s+", 2);
        matcher = CloneMatcher.load(tool.getId(), parts[0], parts[1]);

        System.out.println("Evaluating...");

        ToolEvaluator te;
        te = new ToolEvaluator(
                /*tool_id*/ tool.getId(),
                /*matcher*/ matcher,
                /*similarity_type*/ options.simtype.val,
                /*min_size*/ options.lines.min,
                /*max_size*/ options.lines.max,
                /*min_pretty_size*/options.pretty.min,
                /*max_pretty_size*/options.pretty.max,
                /*min_tokens*/ options.tokens.min,
                /*max_tokens*/ options.tokens.max,
                /*min_judges*/ options.minjudges,
                /*min_confidence*/ options.minconfidence,
                /*include_internal*/ false);

        PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(output.file)));
        long time = System.currentTimeMillis();
        System.out.println("Writing report to " + output.file + "...");
        EvaluateTool.writeReport(pw, tool, te, options.minsim);
        pw.flush();
        pw.close();
        time = System.currentTimeMillis() - time;
        System.err.println("\tElapsed Time: " + time / 1000.0 + "s");

        return null;
    }

    public static void writeReport(PrintWriter pw, Tool tool, ToolEvaluator te, int min_similarity) throws SQLException {

        pw.println("-- Tool --");
        pw.println("       Tool: " + tool.getId() + " - " + tool.getName());
        pw.println("Description: " + tool.getDescription());
        pw.println("    #Clones: " + Clones.numClones(tool.getId()));
        pw.println();
        pw.flush();

        pw.println("-- Versioning --");
        pw.println(" BigCloneEval: " + BigCloneEvalVersion.getVersion());
        pw.println("BigCloneBench: " + BigCloneBenchDB.getVersion());

        pw.println("-- Selected Clones --");
        pw.println("         Min Lines: " + te.getMin_size());
        pw.println("         Max Lines: " + te.getMax_size());
        pw.println("        Min Tokens: " + te.getMin_tokens());
        pw.println("        Max Tokens: " + te.getMax_tokens());
        pw.println("  Min Pretty Lines: " + te.getMin_pretty_size());
        pw.println("  Max Pretty Lines: " + te.getMax_pretty_size());
        pw.println("        Min Judges: " + te.getMin_judges());
        pw.println("    Min Confidence: " + te.getMin_confidence());
        pw.println("          Sim Type: " + te.getSimilarity_type_string());
        pw.println("Minimum Similarity:");
        pw.println();
        pw.flush();

        pw.println("-- Clone Matcher --");
        pw.println(te.getMatcher().toString());
        pw.println();
        pw.flush();

        pw.println("-- Clone Types --");
        pw.println("Type-1");
        pw.println("Type-2");
        pw.println("Very-Strongly Type-3: Clone similarity in range [90,100) after pretty-printing and identifier/literal normalization.");
        pw.println("     Strongly Type-3: Clone similarity in range [70, 90) after pretty-printing and identifier/literal normalization.");
        pw.println("   Moderately Type-3: Clone similarity in range [50, 70) after pretty-printing and identifier/literal normalization.");
        pw.println("Weakly Type-3/Type-4: Clone similarity in range [ 0, 50) after pretty-printing and identifier/literal normalization.");
        pw.println();
        pw.flush();

        pw.println("================================================================================");
        pw.println("\tAll Functionalities");
        pw.println("================================================================================");

        pw.println("-- Recall Per Clone Type (type: numDetected / numClones = recall) --");
        pw.println("              Type-1: " + te.getNumDetected_type1()        + " / " + te.getNumClones_type1()        + " = " + te.getRecall_type1());
        pw.flush();
        pw.println("              Type-2: " + te.getNumDetected_type2()        + " / " + te.getNumClones_type2()        + " = " + te.getRecall_type2());
        pw.flush();
        pw.println("      Type-2 (blind): " + te.getNumDetected_type2b()       + " / " + te.getNumClones_type2b()       + " = " + te.getRecall_type2b());
        pw.println(" Type-2 (consistent): " + te.getNumDetected_type2c()       + " / " + te.getNumClones_type2c()       + " = " + te.getRecall_type2c());
        if(min_similarity <= 90)
            pw.println("Very-Strongly Type-3: " + te.getNumDetected_type3(90, 100) + " / " + te.getNumClones_type3(90, 100) + " = " + te.getRecall_type3(90, 100));
        if(min_similarity <= 70)
            pw.println("     Strongly Type-3: " + te.getNumDetected_type3(70, 90)  + " / " + te.getNumClones_type3(70, 90)  + " = " + te.getRecall_type3(70, 90));
        if(min_similarity <= 50)
            pw.println("    Moderatly Type-3: " + te.getNumDetected_type3(50, 70)  + " / " + te.getNumClones_type3(50, 70)  + " = " + te.getRecall_type3(50, 70));
        if(min_similarity <= 0)
            pw.println("Weakly Type-3/Type-4: " + te.getNumDetected_type3(0, 50)   + " / " + te.getNumClones_type3(0, 50)   + " = " + te.getRecall_type3(0, 50));
        pw.println();


        pw.println("-- Inter-Project Recall Per Clone Type (type: numDetected / numClones = recall)  --");
        pw.println("              Type-1: " + te.getNumDetected_type1_inter()        + " / " + te.getNumClones_type1_inter()        + " = " + te.getRecall_type1_inter());
        pw.println("              Type-2: " + te.getNumDetected_type2_inter()        + " / " + te.getNumClones_type2_inter()        + " = " + te.getRecall_type2_inter());
        pw.println("      Type-2 (blind): " + te.getNumDetected_type2b_inter()       + " / " + te.getNumClones_type2b_inter()       + " = " + te.getRecall_type2b_inter());
        pw.println(" Type-2 (consistent): " + te.getNumDetected_type2c_inter()       + " / " + te.getNumClones_type2c_inter()       + " = " + te.getRecall_type2c_inter());
        if(min_similarity <= 90) {
            pw.println("Very-Strongly Type-3: " + te.getNumDetected_type3_inter(90, 100) + " / " + te.getNumClones_type3_inter(90, 100) + " = " + te.getRecall_type3_inter(90, 100));}
        if(min_similarity <= 70) {
            pw.println("     Strongly Type-3: " + te.getNumDetected_type3_inter(70, 90)  + " / " + te.getNumClones_type3_inter(70, 90)  + " = " + te.getRecall_type3_inter(70, 90));}
        if(min_similarity <= 50) {
            pw.println("    Moderatly Type-3: " + te.getNumDetected_type3_inter(50, 70)  + " / " + te.getNumClones_type3_inter(50, 70)  + " = " + te.getRecall_type3_inter(50, 70));}
        if(min_similarity <= 0) {
            pw.println("Weakly Type-3/Type-4: " + te.getNumDetected_type3_inter(0, 50)   + " / " + te.getNumClones_type3_inter(0, 50)   + " = " + te.getRecall_type3_inter(0, 50));}
        pw.println();

        pw.println("-- Intra-Project Recall Per Clone Type (type: numDetected / numClones = recall) --");
        pw.println("-- Recall Per Clone Type --");
        pw.println("              Type-1: " + te.getNumDetected_type1_intra()        + " / " + te.getNumClones_type1_intra()        + " = " + te.getRecall_type1_intra());
        pw.println("              Type-2: " + te.getNumDetected_type2_intra()        + " / " + te.getNumClones_type2_intra()        + " = " + te.getRecall_type2_intra());
        pw.println("      Type-2 (blind): " + te.getNumDetected_type2b_intra()       + " / " + te.getNumClones_type2b_intra()       + " = " + te.getRecall_type2b_intra());
        pw.println(" Type-2 (consistent): " + te.getNumDetected_type2c_intra()       + " / " + te.getNumClones_type2c_intra()       + " = " + te.getRecall_type2c_intra());
        if(min_similarity <= 90)
            pw.println("Very-Strongly Type-3: " + te.getNumDetected_type3_intra(90, 100) + " / " + te.getNumClones_type3_intra(90, 100) + " = " + te.getRecall_type3_intra(90, 100));
        if(min_similarity <= 70)
            pw.println("     Strongly Type-3: " + te.getNumDetected_type3_intra(70, 90)  + " / " + te.getNumClones_type3_intra(70, 90)  + " = " + te.getRecall_type3_intra(70, 90));
        if(min_similarity <= 50)
            pw.println("    Moderatly Type-3: " + te.getNumDetected_type3_intra(50, 70)  + " / " + te.getNumClones_type3_intra(50, 70)  + " = " + te.getRecall_type3_intra(50, 70));
        if(min_similarity <= 0)
            pw.println("Weakly Type-3/Type-4: " + te.getNumDetected_type3_intra(0, 50)   + " / " + te.getNumClones_type3_intra(0, 50)   + " = " + te.getRecall_type3_intra(0, 50));
        pw.println();

        int base = min_similarity;

        pw.println("-- Type-3 Recall per 5% Region ([start,end]: numDetected / numClones = recall)  --");
        for(int start = base; start <= 95; start+=5) {
            int end = start+5;
            pw.println("[" + start + "," + end + "]: " + te.getNumDetected_type3(start, end) + " / " + te.getNumClones_type3(start,end) + " = " + te.getRecall_type3(start, end));
        }
        pw.println();
        pw.flush();

        pw.println("-- Type-3 Inter-Project Recall per 5% Region--");
        for(int start = base; start <= 95; start+=5) {
            int end = start+5;
            pw.println("[" + start + "," + end + "]: " + te.getNumDetected_type3_inter(start, end) + " / " + te.getNumClones_type3_inter(start,end) + " = " + te.getRecall_type3_inter(start, end));
        }
        pw.println();
        pw.flush();

        pw.println("-- Type-3 Intra-Project Recall per 5% Region--");
        for(int start = base; start <= 95; start+=5) {
            int end = start+5;
            pw.println("[" + start + "," + end + "]: " + te.getNumDetected_type3_intra(start, end) + " / " + te.getNumClones_type3_intra(start,end) + " = " + te.getRecall_type3_intra(start, end));
        }
        pw.println();
        pw.flush();

        pw.println("-- Type-3 Recall Per Minimum Similarity --");
        for(int start = base; start <= 95; start+=5) {
            pw.println("[" + start + "," + 100 + "]: " + te.getNumDetected_type3(start, 100) + " / " + te.getNumClones_type3(start,100) + " = " + te.getRecall_type3(start, 100));
        }
        pw.println();
        pw.flush();

        pw.println("-- Type-3 Inter-Project Recall Per Minimum Similarity --");
        for(int start = base; start <= 95; start+=5) {
            pw.println("[" + start + "," + 100 + "]: " + te.getNumDetected_type3_inter(start, 100) + " / " + te.getNumClones_type3_inter(start,100) + " = " + te.getRecall_type3_inter(start, 100));
        }
        pw.println();
        pw.flush();

        pw.println("-- Type-3 Intra-Project Recall Per Minimum Similarity --");
        for(int start = base; start <= 95; start+=5) {
            pw.println("[" + start + "," + 100 + "]: " + te.getNumDetected_type3_intra(start, 100) + " / " + te.getNumClones_type3_intra(start,100) + " = " + te.getRecall_type3_intra(start, 100));
        }
        pw.println();
        pw.flush();

        // Set<Long> fids = Functionalities.getFunctionalityIds();
        Set<Long> fids = new HashSet<Long>();
        fids.add(6L);

        for(long fid : fids) {
            System.out.println("Evaluating functionality " + fid + "...");
            Functionality f = Functionalities.getFunctinality(fid);
            pw.println("================================================================================");
            pw.println("Functionality");
            pw.println("  id: " + fid);
            pw.println("name: " + f.getName());
            pw.println("desc: " + f.getDesc());
            pw.println("================================================================================");

            pw.println("-- Recall Per Clone Type (type: numDetected / numClones = recall) --");
            pw.println("              Type-1: " + te.getNumDetected_type1(fid)        + " / " + te.getNumClones_type1(fid)        + " = " + te.getRecall_type1(fid));
            pw.flush();
            pw.println("              Type-2: " + te.getNumDetected_type2(fid)        + " / " + te.getNumClones_type2(fid)        + " = " + te.getRecall_type2(fid));
            pw.flush();
            pw.println("      Type-2 (blind): " + te.getNumDetected_type2b(fid)       + " / " + te.getNumClones_type2b(fid)       + " = " + te.getRecall_type2b(fid));
            pw.println(" Type-2 (consistent): " + te.getNumDetected_type2c(fid)       + " / " + te.getNumClones_type2c(fid)       + " = " + te.getRecall_type2c(fid));
            if(min_similarity <= 90)
                pw.println("Very-Strongly Type-3: " + te.getNumDetected_type3(fid, 90, 100) + " / " + te.getNumClones_type3(fid, 90, 100) + " = " + te.getRecall_type3(fid, 90, 100));
            if(min_similarity <= 70)
                pw.println("     Strongly Type-3: " + te.getNumDetected_type3(fid, 70, 90)  + " / " + te.getNumClones_type3(fid, 70, 90)  + " = " + te.getRecall_type3(fid, 70, 90));
            if(min_similarity <= 50)
                pw.println("    Moderatly Type-3: " + te.getNumDetected_type3(fid, 50, 70)  + " / " + te.getNumClones_type3(fid, 50, 70)  + " = " + te.getRecall_type3(fid, 50, 70));
            if(min_similarity <= 0)
                pw.println("Weakly Type-3/Type-4: " + te.getNumDetected_type3(fid, 0, 50)   + " / " + te.getNumClones_type3(fid, 0, 50)   + " = " + te.getRecall_type3(fid, 0, 50));
            pw.println();


            pw.println("-- Inter-Project Recall Per Clone Type (type: numDetected / numClones = recall)  --");
            pw.println("              Type-1: " + te.getNumDetected_type1_inter(fid)        + " / " + te.getNumClones_type1_inter(fid)        + " = " + te.getRecall_type1_inter(fid));
            pw.println("              Type-2: " + te.getNumDetected_type2_inter(fid)        + " / " + te.getNumClones_type2_inter(fid)        + " = " + te.getRecall_type2_inter(fid));
            pw.println("      Type-2 (blind): " + te.getNumDetected_type2b_inter(fid)       + " / " + te.getNumClones_type2b_inter(fid)       + " = " + te.getRecall_type2b_inter(fid));
            pw.println(" Type-2 (consistent): " + te.getNumDetected_type2c_inter(fid)       + " / " + te.getNumClones_type2c_inter(fid)       + " = " + te.getRecall_type2c_inter(fid));
            if(min_similarity <= 90) {
                pw.println("Very-Strongly Type-3: " + te.getNumDetected_type3_inter(fid, 90, 100) + " / " + te.getNumClones_type3_inter(fid, 90, 100) + " = " + te.getRecall_type3_inter(fid, 90, 100));}
            if(min_similarity <= 70) {
                pw.println("     Strongly Type-3: " + te.getNumDetected_type3_inter(fid, 70, 90)  + " / " + te.getNumClones_type3_inter(fid, 70, 90)  + " = " + te.getRecall_type3_inter(fid, 70, 90));}
            if(min_similarity <= 50) {
                pw.println("    Moderatly Type-3: " + te.getNumDetected_type3_inter(fid, 50, 70)  + " / " + te.getNumClones_type3_inter(fid, 50, 70)  + " = " + te.getRecall_type3_inter(fid, 50, 70));}
            if(min_similarity <= 0) {
                pw.println("Weakly Type-3/Type-4: " + te.getNumDetected_type3_inter(fid, 0, 50)   + " / " + te.getNumClones_type3_inter(fid, 0, 50)   + " = " + te.getRecall_type3_inter(fid, 0, 50));}
            pw.println();

            pw.println("-- Intra-Project Recall Per Clone Type (type: numDetected / numClones = recall) --");
            pw.println("-- Recall Per Clone Type --");
            pw.println("              Type-1: " + te.getNumDetected_type1_intra(fid)        + " / " + te.getNumClones_type1_intra(fid)        + " = " + te.getRecall_type1_intra(fid));
            pw.println("              Type-2: " + te.getNumDetected_type2_intra(fid)        + " / " + te.getNumClones_type2_intra(fid)        + " = " + te.getRecall_type2_intra(fid));
            pw.println("      Type-2 (blind): " + te.getNumDetected_type2b_intra(fid)       + " / " + te.getNumClones_type2b_intra(fid)       + " = " + te.getRecall_type2b_intra(fid));
            pw.println(" Type-2 (consistent): " + te.getNumDetected_type2c_intra(fid)       + " / " + te.getNumClones_type2c_intra(fid)       + " = " + te.getRecall_type2c_intra(fid));
            if(min_similarity <= 90)
                pw.println("Very-Strongly Type-3: " + te.getNumDetected_type3_intra(fid, 90, 100) + " / " + te.getNumClones_type3_intra(fid, 90, 100) + " = " + te.getRecall_type3_intra(fid, 90, 100));
            if(min_similarity <= 70)
                pw.println("     Strongly Type-3: " + te.getNumDetected_type3_intra(fid, 70, 90)  + " / " + te.getNumClones_type3_intra(fid, 70, 90)  + " = " + te.getRecall_type3_intra(fid, 70, 90));
            if(min_similarity <= 50)
                pw.println("    Moderatly Type-3: " + te.getNumDetected_type3_intra(fid, 50, 70)  + " / " + te.getNumClones_type3_intra(fid, 50, 70)  + " = " + te.getRecall_type3_intra(fid, 50, 70));
            if(min_similarity <= 0)
                pw.println("Weakly Type-3/Type-4: " + te.getNumDetected_type3_intra(fid, 0, 50)   + " / " + te.getNumClones_type3_intra(fid, 0, 50)   + " = " + te.getRecall_type3_intra(fid, 0, 50));
            pw.println();

            pw.println("-- Type-3 Recall per 5% Region ([start,end]: numDetected / numClones = recall)  --");
            for(int start = base; start <= 95; start+=5) {
                int end = start+5;
                pw.println("[" + start + "," + end + "]: " + te.getNumDetected_type3(fid, start, end) + " / " + te.getNumClones_type3(fid, start,end) + " = " + te.getRecall_type3(fid, start, end));
            }
            pw.println();
            pw.flush();

            pw.println("-- Type-3 Inter-Project Recall per 5% Region--");
            for(int start = base; start <= 95; start+=5) {
                int end = start+5;
                pw.println("[" + start + "," + end + "]: " + te.getNumDetected_type3_inter(fid, start, end) + " / " + te.getNumClones_type3_inter(fid, start,end) + " = " + te.getRecall_type3_inter(fid, start, end));
            }
            pw.println();
            pw.flush();

            pw.println("-- Type-3 Intra-Project Recall per 5% Region--");
            for(int start = base; start <= 95; start+=5) {
                int end = start+5;
                pw.println("[" + start + "," + end + "]: " + te.getNumDetected_type3_intra(fid, start, end) + " / " + te.getNumClones_type3_intra(fid, start,end) + " = " + te.getRecall_type3_intra(fid, start, end));
            }
            pw.println();
            pw.flush();

            pw.println("-- Type-3 Recall Per Minimum Similarity --");
            for(int start = base; start <= 95; start+=5) {
                pw.println("[" + start + "," + 100 + "]: " + te.getNumDetected_type3(fid, start, 100) + " / " + te.getNumClones_type3(fid, start,100) + " = " + te.getRecall_type3(fid, start, 100));
            }
            pw.println();
            pw.flush();

            pw.println("-- Type-3 Inter-Project Recall Per Minimum Similarity --");
            for(int start = base; start <= 95; start+=5) {
                pw.println("[" + start + "," + 100 + "]: " + te.getNumDetected_type3_inter(fid, start, 100) + " / " + te.getNumClones_type3_inter(fid, start,100) + " = " + te.getRecall_type3_inter(fid, start, 100));
            }
            pw.println();
            pw.flush();

            pw.println("-- Type-3 Intra-Project Recall Per Minimum Similarity --");
            for(int start = base; start <= 95; start+=5) {
                pw.println("[" + start + "," + 100 + "]: " + te.getNumDetected_type3_intra(fid, start, 100) + " / " + te.getNumClones_type3_intra(fid, start,100) + " = " + te.getRecall_type3_intra(fid, start, 100));
            }
            pw.println();
            pw.flush();
        }

    }

}
