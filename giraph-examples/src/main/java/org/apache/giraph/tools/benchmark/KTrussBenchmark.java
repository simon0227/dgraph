package org.apache.giraph.tools.benchmark;

import org.apache.commons.cli.CommandLine;
import org.apache.giraph.GiraphRunner;
import org.apache.giraph.benchmark.BenchmarkOption;
import org.apache.giraph.conf.GiraphConfiguration;
import org.apache.giraph.partition.IdenticalPartitionerFactory;
import org.apache.giraph.subgraph.KTrussDecompositionSubgraph;
import org.apache.giraph.subgraph.KTrussSubgraphImpr;
import org.apache.giraph.subgraph.SimpleGraphStore;
import org.apache.giraph.tools.graphanalytics.peta.ImprovedNormalKTrussVertex;
import org.apache.giraph.tools.graphanalytics.peta.NormalKTrussVertex;
import org.apache.giraph.utils.ConfigurationUtils;
import org.apache.hadoop.util.ToolRunner;

public class KTrussBenchmark extends GiraphRunner {

	static{
		ConfigurationUtils.addOption("th", "thredshold", true, "thredshold for the k truss");
		ConfigurationUtils.addOption("t", "testType", true, "specify the test type");
		ConfigurationUtils.addOption("top", "top", true, "select the top k truss");
		ConfigurationUtils.addOption("ps", "partitionScheme", true, "specify the path of the partition scheme");
	}
	
	 protected  void prepareConfiguration(GiraphConfiguration conf, CommandLine cmd) {
		 String testType = cmd.getOptionValue("t");
		 /* new options */
		conf.setInt("giraph.ktruss.threshold", Integer.valueOf(cmd.getOptionValue("th")));
		
		/* fixed complementary options */
		conf.setBoolean("giraph.ktruss", true);
		conf.setInt("giraph.userPartitionCount", Integer.valueOf(BenchmarkOption.WORKERS.getOptionValue(cmd)));
		
		if(cmd.hasOption("ps")){
//			System.out.println("using identical partitioner.");
			conf.set("giraph.engine", "page");
			conf.set("giraph.partitionscheme.path", cmd.getOptionValue("ps"));
			conf.setGraphPartitionerFactoryClass(IdenticalPartitionerFactory.class);
		}
		
		/* set some dependent classes */
		if("subgraph".equals(testType)){
			/* set for the partition based approach */
			conf.setBoolean("giraph.ktruss.subgraph", true);
//			conf.setPartitionClass(BasicGraphStore.class);
			conf.setPartitionClass(SimpleGraphStore.class);
			conf.setMasterComputeClass(KTrussSubgraphImpr.AggregatorsMasterCompute.class);			
		}
		else if("decomp".equals(testType)){
			conf.setBoolean("giraph.ktruss.subgraph", true);
			conf.set("giraph.ktruss.subgraph.program", "decomposition");
//			conf.setPartitionClass(BasicGraphStore.class);
			conf.setPartitionClass(SimpleGraphStore.class);
			conf.setMasterComputeClass(KTrussDecompositionSubgraph.AggregatorsMasterCompute.class);
		}
		else if("topt".equals(testType)){
			conf.setBoolean("giraph.ktruss.subgraph", true);
			conf.setInt("giraph.ktruss.subgraph.topt", Integer.valueOf(cmd.getOptionValue("top")));
			conf.set("giraph.ktruss.subgraph.program", "topt");
			conf.setPartitionClass(SimpleGraphStore.class);
//			conf.setMasterComputeClass(TopTKTrussSubgraph.AggregatorsMasterCompute.class);
			
		}
		else if("impl".equals(testType)){
			conf.setBoolean("giraph.ktruss.impl", true);
//			conf.setBoolean("giraph.ktruss.normal", true);
			conf.setMasterComputeClass(ImprovedNormalKTrussVertex.AggregatorsMasterCompute.class);
		}
		else if("tc".equals(testType)){
			
		}
		else{
			/* set for the baseline */
			conf.setBoolean("giraph.ktruss.normal", true);
			conf.setMasterComputeClass(NormalKTrussVertex.AggregatorsMasterCompute.class);
		}
	 }
	 
	 public static void main(String[] args) throws Exception {
		 System.exit(ToolRunner.run(new KTrussBenchmark(), args));
	}
}
