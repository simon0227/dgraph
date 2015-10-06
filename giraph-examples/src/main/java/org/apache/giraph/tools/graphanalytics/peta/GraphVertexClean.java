package org.apache.giraph.tools.graphanalytics.peta;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.giraph.aggregators.IntMaxAggregator;
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
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.google.common.collect.Lists;

@Algorithm(
		name = "GraphVertexClean",
		description = "Remove Vertex according some rules."
)
public class GraphVertexClean extends 
Vertex<IntWritable, IntWritable, NullWritable, IntWritable>{

	private final static String AGG_DELETEID = "RemoveId";
	private final static String AGG_MAXDEGREE = "MaxDegree";
	private final static int STOP_FLAG = -1;
	private int threshold = 100000;
	
	private int isDeleted = -2;
	
	@Override
	public void compute(Iterable<IntWritable> messages) throws IOException {
		if(((IntWritable)getAggregatedValue(AGG_DELETEID)).get() == STOP_FLAG){
			voteToHalt();
			return ;
		}
		switch((int)(getSuperstep() % 3)){
			case 0:
				if(getValue().get() != -2 && getNumEdges() > threshold){
					aggregate(AGG_MAXDEGREE, new IntWritable(getNumEdges()));
				}
				break;
			case 1:
				int maxDegree = ((IntWritable)getAggregatedValue(AGG_MAXDEGREE)).get();
				if(getValue().get() != -2 && getNumEdges() == maxDegree){
					aggregate(AGG_DELETEID, getId());
				}
				else{
					aggregate(AGG_DELETEID, new IntWritable(STOP_FLAG));
				}
				break;
			case 2:
				int deleteId = ((IntWritable)getAggregatedValue(AGG_DELETEID)).get();
				if(deleteId == getId().get()){
					this.setValue(new IntWritable(isDeleted));
					sendMessageToAllEdges(new IntWritable(deleteId));
				}
				break;
			case 3:
				for(IntWritable msg : messages){
					Iterator<MutableEdge<IntWritable, NullWritable>> iter = getMutableEdges().iterator();
					while(iter.hasNext()){
						MutableEdge<IntWritable, NullWritable> edge = iter.next();
						if(edge.getTargetVertexId().get() == msg.get()){
							iter.remove();
							break;
						}
					}
				}
				break;
		}
	}

	
	/** Master compute which uses aggregators. */
	  public static class AggregatorsMasterCompute extends
	      DefaultMasterCompute {
		  int cnt = 0;
	    @Override
	    public void compute() {
			int deleteId = ((IntWritable)getAggregatedValue(AGG_DELETEID)).get();
			if(deleteId > 0){
				cnt++;
		    	System.out.println("Delete "+cnt+"th vertex: "+getAggregatedValue(AGG_DELETEID));
			}
	    }

	    @Override
	    public void initialize() throws InstantiationException,
	        IllegalAccessException {
		  registerAggregator(AGG_MAXDEGREE, IntMaxAggregator.class);
	      registerAggregator(AGG_DELETEID, IntMaxAggregator.class);
	    }
	  }

	/** Vertex InputFormat */
	public static class GraphVertexCleanInputFormat extends
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

				/**
				 * Only store edge (low degree -> high degree).
				 */
				@Override
				protected Iterable<Edge<IntWritable, NullWritable>> getEdges(String[] values) throws
				IOException {
					int i = 1;
					List<Edge<IntWritable, NullWritable>> edges = Lists.newLinkedList();
					int id = Integer.valueOf(values[0]);
					while (i < values.length) {
						int target = Integer.valueOf(values[i]);
						edges.add(EdgeFactory.create(new IntWritable(target), NullWritable.get()));
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
	public static class GraphVertexCleanOutputFormat extends
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
					if(vertex.getValue().get() == -2 || vertex.getNumEdges() == 0){
						/* -2 is the delete flag */
						return ;
					}
					
					StringBuilder neighbors = new StringBuilder();
					for(Edge<IntWritable, NullWritable> edge : vertex.getEdges()){
						neighbors.append(edge.getTargetVertexId().toString() + " ");
					}
					getRecordWriter().write(
						new Text(vertex.getId().toString()),
						new Text(neighbors.toString()));
				}
		}
	}	
}
