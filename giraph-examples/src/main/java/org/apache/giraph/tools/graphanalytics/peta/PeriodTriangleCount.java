package org.apache.giraph.tools.graphanalytics.peta;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.giraph.aggregators.BooleanAndAggregator;
import org.apache.giraph.aggregators.LongSumAggregator;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.edge.EdgeFactory;
import org.apache.giraph.edge.MutableEdge;
import org.apache.giraph.examples.Algorithm;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.formats.AdjacencyListTextVertexInputFormat;
import org.apache.giraph.io.formats.TextVertexOutputFormat;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.giraph.subgraph.TripleWritable;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.google.common.collect.Lists;

@Algorithm(
		name = "TriangleCount",
		description = "calculate the edge replicating factor upper bound in TC-subgraph model through triangle counting" +
				"and the input graph should be ordered."
)
public class PeriodTriangleCount extends 
Vertex<IntWritable, IntWritable, NullWritable, TripleWritable>{

	
	private static final String AGG_TRIANGLE = "Triangles";
	private static final String AGG_STOPFLAG = "Stopflag";
	
	private int progress = 0;
	private int threshold = 6710886; /* send 100M, 16B per msg */
	
	@Override
	public void compute(Iterable<TripleWritable> messages) throws IOException {
		/**
		 * 1. send query
		 * 2. answer
		 * 3. calculate
		 */
		boolean isStop = ((BooleanWritable)this.getAggregatedValue(AGG_STOPFLAG)).get();
		if(isStop && getSuperstep() > 0){
			voteToHalt();
			return ;
		}
		switch((int)(getSuperstep() % 2)){
			case 0:
				/* triangle complete phase: send query of checking edge existence */
				int sentMessage = 0;
				int tmpProgress = 0;
				for(Edge<IntWritable, NullWritable> edgeOne : getEdges()){
					tmpProgress++;
					if(tmpProgress < progress) continue;
					int vidOne = edgeOne.getTargetVertexId().get();
					for(Edge<IntWritable, NullWritable> edgeTwo : getEdges()){
						int vidTwo = edgeTwo.getTargetVertexId().get();
	//					if( vidOne == vidTwo ){
	//						continue;
	//					}
						/**
						 * Here the TripleWritable stores:
						 * first:  Source Id of the message;
						 * second: The source Id of edge to be checked;
						 * third:  The target Id of edge to be checked.
						 */
						if(vidOne < vidTwo){
							sendMessage(edgeOne.getTargetVertexId(), new TripleWritable(getId().get(), vidOne, vidTwo));
							sentMessage++;
						}
					}
					if(sentMessage >= threshold){
						break;
					}
				}
				progress = tmpProgress;
				aggregate(AGG_STOPFLAG, new BooleanWritable(false));
				break;
			case 1:
				/* check edge existence and report to the source of edge */
				long localTC = 0;
				for(TripleWritable msg : messages){
					for(Edge<IntWritable, NullWritable> edge : getEdges()){
						if(msg.getThird() == edge.getTargetVertexId().get()){
							localTC++;
						}
					}
				}
				aggregate(AGG_TRIANGLE, new LongWritable(localTC));
				if(progress < this.getNumEdges()){
					aggregate(AGG_STOPFLAG, new BooleanWritable(false));
				}
			}
	}

	/** Master compute which uses aggregators. */
	  public static class AggregatorsMasterCompute extends
	      DefaultMasterCompute {
	    @Override
	    public void compute() {
//	    	if(getSuperstep() == 2){
	    		long totalTriangles = ((LongWritable)getAggregatedValue(AGG_TRIANGLE)).get();
	    		System.out.println("Triangle: "+totalTriangles);
//	    	}
	    }

	    @Override
	    public void initialize() throws InstantiationException,
	        IllegalAccessException {
	      registerPersistentAggregator(AGG_TRIANGLE, LongSumAggregator.class);
	      registerAggregator(AGG_STOPFLAG, BooleanAndAggregator.class);
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
						if(id < target){
							edges.add(EdgeFactory.create(new IntWritable(target), NullWritable.get()));
						}
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
