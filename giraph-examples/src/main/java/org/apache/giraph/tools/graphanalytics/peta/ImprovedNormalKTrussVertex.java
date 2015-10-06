package org.apache.giraph.tools.graphanalytics.peta;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.giraph.aggregators.BooleanAndAggregator;
import org.apache.giraph.aggregators.BooleanOrAggregator;
import org.apache.giraph.aggregators.LongSumAggregator;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.edge.EdgeFactory;
import org.apache.giraph.edge.MutableEdge;
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
import org.apache.log4j.Logger;

import com.google.common.collect.Lists;

public class ImprovedNormalKTrussVertex extends 
Vertex<IntWritable, IntWritable, NullWritable, TripleWritable>{

	private static Logger LOG = Logger.getLogger(ImprovedNormalKTrussVertex.class);
	
	 /** Name of regular aggregator */
	private static final String CHANGE_AGG = "IsReallyChange";
	private static final String AGG_REMOTEMSG = "remotemessage";
	
	private HashMap<Integer, Integer> count = new HashMap<Integer, Integer>();
	private int threshold;
	
	
	/**
	 * Optimize for the baseline, with additional index.
	 */
	private HashSet<Integer> neighborIndex = new HashSet<Integer>();;
	
	/**
	 * This vertex only works on the ordered graph, where the larger label implies
	 * the higher degree. And the each vertex only stores out-going edges.
	 */
	@Override
	public void compute(Iterable<TripleWritable> messages) throws IOException {
		
		int curStep = (int)(getSuperstep() % 3L);
		if(curStep == 0){
			/* triangle complete phase: send query of checking edge existence */
			if(getSuperstep() != 0){
				if(!((BooleanWritable)getAggregatedValue(CHANGE_AGG)).get()){
					voteToHalt();
					return ;
				}
			}
			neighborIndex.clear();
			for(Edge<IntWritable, NullWritable> edgeOne : getEdges()){
				int vidOne = edgeOne.getTargetVertexId().get();
				/* index building */
				neighborIndex.add(vidOne);
				for(Edge<IntWritable, NullWritable> edgeTwo : getEdges()){
					int vidTwo = edgeTwo.getTargetVertexId().get();
					/**
					 * Here the TripleWritable stores:
					 * first:  Source Id of the message;
					 * second: The source Id of edge to be checked;
					 * third:  The target Id of edge to be checked.
					 */
					if(vidOne < vidTwo)
						sendMessage(edgeOne.getTargetVertexId(), new TripleWritable(getId().get(), vidOne, vidTwo));
				}
			}
		}
		else if(curStep == 1){
			/* check edge existence and report to the source of edge */
			for(TripleWritable msg : messages){
				/**
				for(Edge<IntWritable, NullWritable> edge : getEdges()){
					if(msg.getThird() == edge.getTargetVertexId().get()){
					**/
				if(this.neighborIndex.contains(msg.getThird())){
						/**
						 * Here the TripbleWritable stores:
						 * first:  Source Id of the message;
						 * second: The source Id of the edge belong to a triangle
						 * third:  The target Id of the edge belong to a triangle 
						 */
						sendMessage(getId(), new TripleWritable(msg.getSecond(), msg.getSecond(), msg.getThird()));
						sendMessage(new IntWritable(msg.getFirst()), new TripleWritable(msg.getSecond(), msg.getFirst(), msg.getThird()));
						sendMessage(new IntWritable(msg.getFirst()), new TripleWritable(msg.getSecond(), msg.getFirst(), msg.getSecond()));
					}
//				}
			}
		}
		else if(curStep == 2){
			/* count the triangle, remove invalid edge and report to neighbors*/
			count.clear();
			threshold = getConf().getInt("giraph.ktruss.threshold", 2) - 2;
//			LOG.info("Current Threshold = "+ threshold);

//			long start_time = System.currentTimeMillis();
//			long message = 0;
			for(TripleWritable msg : messages){
//				LOG.info("MSG: vid="+getId()+", "+msg.toString());
				int target = msg.getThird();
//				message++;
				if(count.get(target) == null){
					count.put(target, 1);
				}
				else{
					count.put(target, count.get(target) + 1);
				}
			}

//			long mid_time = System.currentTimeMillis();
			boolean isChanged = false;
			Iterator<MutableEdge<IntWritable, NullWritable>> iter = getMutableEdges().iterator();
			while(iter.hasNext()){
				MutableEdge<IntWritable, NullWritable> edge = iter.next();
				
				if(count.get(edge.getTargetVertexId().get()) == null ||
						count.get(edge.getTargetVertexId().get()) < threshold){
					/* remove edge locally */
					iter.remove();
					/* aggregate local partial aggregated value for this superstep*/
					isChanged = true;
				}
			}
//			long end_time = System.currentTimeMillis();
//			this.addCountTime(mid_time - start_time);
//			this.addPruneTime(end_time - mid_time);
//			this.addMessage(message);

		    aggregate(CHANGE_AGG, new BooleanWritable(isChanged));
		}
	}
	
	
	/** Master compute which uses aggregators. */
	  public static class AggregatorsMasterCompute extends
	      DefaultMasterCompute {
	    @Override
	    public void compute() {
			long remoteMessage = ((LongWritable)getAggregatedValue(AGG_REMOTEMSG)).get();
			System.out.println("step= "+getSuperstep()+": remotemsg="+remoteMessage);//+" State="+getAggregatedValue(CHANGE_AGG));
		
	    }

	    @Override
	    public void initialize() throws InstantiationException,
	        IllegalAccessException {
	      registerAggregator(CHANGE_AGG, BooleanOrAggregator.class);
	      registerPersistentAggregator(AGG_REMOTEMSG, LongSumAggregator.class);
	    }
	  }

	/** Vertex InputFormat */
	public static class ImprovedNormalKTrussVertexInputFormat extends
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
	public static class ImprovedNormalKTrussVertexOutputFormat extends
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
//				private int zeroDegreeCounter = 0;
				@Override
				public void writeVertex(
						Vertex<IntWritable, IntWritable, NullWritable, ?> vertex)
				throws IOException, InterruptedException {

					String neighbors = "";
					for(Edge<IntWritable, NullWritable> edge : vertex.getEdges()){
						neighbors += edge.getTargetVertexId().toString() + " ";
					}
					getRecordWriter().write(
						new Text(vertex.getId().toString()),
						new Text(neighbors));
				}
		}
	}	
}
