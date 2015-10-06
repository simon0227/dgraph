package org.apache.giraph.tools.graphanalytics.peta;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.giraph.aggregators.LongSumAggregator;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.edge.EdgeFactory;
import org.apache.giraph.examples.Algorithm;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.formats.AdjacencyListTextVertexInputFormat;
import org.apache.giraph.io.formats.TextVertexOutputFormat;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.google.common.collect.Lists;

@Algorithm(
		name = "EdgeFactorUB",
		description = "calculate the edge replicating factor upper bound in TC-subgraph model."
)
public class EdgeFactorUB extends 
Vertex<IntWritable, IntWritable, NullWritable, IntWritable>{

	
	private static final String AGG_MINDEG = "MinDegreeSum";
	private static final String AGG_EDGE = "EdgeSize";
	
	@Override
	public void compute(Iterable<IntWritable> messages) throws IOException {
		/**
		 * 1. send degree info
		 * 2. aggregate degree info and edge info
		 * 3. end
		 */
		if(getSuperstep() == 0){
			this.sendMessageToAllEdges(new IntWritable(this.getNumEdges()));
		}
		if(getSuperstep() == 1){
			long localMinDegree = 0;
			int degree = this.getNumEdges();
			for(IntWritable msg : messages){
				localMinDegree += degree < msg.get() ? degree : msg.get();
			}
			aggregate(AGG_MINDEG, new LongWritable(localMinDegree));
			aggregate(AGG_EDGE, new LongWritable(degree));
		}
		else{
			voteToHalt();
		}
	}

	/** Master compute which uses aggregators. */
	  public static class AggregatorsMasterCompute extends
	      DefaultMasterCompute {
	    @Override
	    public void compute() {
	    	if(getSuperstep() == 2){
	    		long totalEdge = ((LongWritable)getAggregatedValue(AGG_EDGE)).get();
	    		long totalMinDegree = ((LongWritable)getAggregatedValue(AGG_MINDEG)).get();
	    		System.out.println("Edges: "+totalEdge+
	    				" MinDeg: "+totalMinDegree+
	    				" Factor: "+ (totalMinDegree*1.0/totalEdge+1));
	    	}
	    }

	    @Override
	    public void initialize() throws InstantiationException,
	        IllegalAccessException {
	      registerAggregator(AGG_MINDEG, LongSumAggregator.class);
	      registerAggregator(AGG_EDGE, LongSumAggregator.class);
	    }
	  }

	/** Vertex InputFormat */
	public static class EdgeFactorUBInputFormat extends
		AdjacencyListTextVertexInputFormat<IntWritable, IntWritable, NullWritable> {
			/** Separator for id and value */
			private static final Pattern SEPARATOR = Pattern.compile("[\t ]");

			@Override
			public AdjacencyListTextVertexReader createVertexReader(
					InputSplit split, TaskAttemptContext context) {
				return new OrderedGraphReader();
			}

			public class  OrderedGraphReader extends AdjacencyListTextVertexReader {

				protected String[] preprocessLine(Text line) throws IOException {
					String[] values = SEPARATOR.split(line.toString());
					return values;
				}

				@Override
				protected IntWritable getId(String[] values) throws IOException {
					return decodeId(values[0]);
				}

				@Override
				protected IntWritable getValue(String[] values) throws IOException {
					return decodeValue(null);
				}

				@Override
				protected Iterable<Edge<IntWritable, NullWritable>> getEdges(String[] values) throws
				IOException {
					int i = 1;
					List<Edge<IntWritable, NullWritable>> edges = Lists.newLinkedList();
					int id = Integer.valueOf(values[0]);
					while (i < values.length) {
						int target = Integer.valueOf(values[i]);
						//if(id < target){
							edges.add(EdgeFactory.create(new IntWritable(target), NullWritable.get()));
						//}
						i++;
					}
					return edges;
				}

				@Override
				public IntWritable decodeId(String s) {
					return new IntWritable(Integer.valueOf(s));
				}

				@Override
				public IntWritable decodeValue(String s) {
					return new IntWritable(0);
				}

				@Override
				public Edge<IntWritable, NullWritable> decodeEdge(String id,
						String value) {
					return null;
				}
			}
		} 

	/**
	 * Simple VertexOutputFormat that supports {@link SimplePageRankVertex}
	 */
	public static class EdgeFactorUBOutputFormat extends
		TextVertexOutputFormat<IntWritable, IntWritable, NullWritable> {
			@Override
			public TextVertexWriter createVertexWriter(TaskAttemptContext context)
			throws IOException, InterruptedException {
			return new OrderedGraphWriter();
			}

			/**
			 * Simple VertexWriter that supports {@link SimplePageRankVertex}
			 */
			public class OrderedGraphWriter extends TextVertexWriter {
				@Override
				public void writeVertex(
						Vertex<IntWritable, IntWritable, NullWritable, ?> vertex)
				throws IOException, InterruptedException {
//					String neighbors = "";
//					for(Edge<IntWritable, NullWritable> edge : vertex.getEdges()){
//						neighbors += edge.getTargetVertexId().toString() + " ";
//					}
					getRecordWriter().write(
						new Text(vertex.getId().toString()),
						new Text(String.valueOf(vertex.getNumEdges())));
				}
		}
	}	
}
