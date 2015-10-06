package org.apache.giraph.tools.graphanalytics.peta;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.giraph.aggregators.LongSumAggregator;
import org.apache.giraph.aggregators.LongMaxAggregator;
import org.apache.giraph.aggregators.LongMinAggregator;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.edge.EdgeFactory;
import org.apache.giraph.examples.Algorithm;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.formats.AdjacencyListTextVertexInputFormat;
import org.apache.giraph.io.formats.TextVertexOutputFormat;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.giraph.subgraph.TripleWritable;
//import org.apache.giraph.tools.utils.Distributor;
//import org.apache.giraph.tools.utils.WorkloadEstimationDistributor;
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
public class TriangleCount extends 
Vertex<IntWritable, IntWritable, NullWritable, TripleWritable>{


//	private static final String AGG_MAXMSG = "MaxSendMsg";
	private static final String AGG_TRIANGLE = "Triangles";
//	private static final String AGG_MAXDEG = "MaxDegree";
	private static final String AGG_MAXCUTTHETA = "MaxCutTheta";
	private static final String AGG_MINCUTTHETA = "MinCutTheta";
//	private static final String AGG_WORKLOADDIST= "WorkloadDistribution";
//	private static final String AGG_WORKLOADDIST2= "WorkloadDistribution-aware";
	
	private final IntWritable targetStub = new IntWritable();
	private final TripleWritable msgStub = new TripleWritable();
	
	private HashMap<Integer, Integer> count = new HashMap<Integer, Integer>();
//	private int partitionNum;
	
//	private HashMap<Integer, Integer> count2 = new HashMap<Integer, Integer>();
////	private Distributor dist;
//	private int [] candidate;
	
	@Override
	public void compute(Iterable<TripleWritable> messages) throws IOException {
		/**
		 * 1. send query
		 * 2. answer
		 * 3. calculate
		 */
		
//		if(getSuperstep() == 0){
//			aggregate(AGG_MAXDEG, new LongWritable(this.getNumEdges()));
//			this.partitionNum = getConf().getInt(GiraphConstants.USER_PARTITION_COUNT.getKey(), 60);
//			for(int i = 0; i < partitionNum; i++){
//				count.put(i, 0);
//				count.put(i, 0);
//			}
////			dist = new WorkloadEstimationDistributor();
////			dist.initialization(getConf());
////			candidate = new int[2];
//		}
//		else 
		if(getSuperstep() == 0){
			/* triangle complete phase: send query of checking edge existence */
//			for(Edge<IntWritable, NullWritable> edgeOne : getEdges()){
//				int vidOne = edgeOne.getTargetVertexId().get();
//				for(Edge<IntWritable, NullWritable> edgeTwo : getEdges()){
//					int vidTwo = edgeTwo.getTargetVertexId().get();
////					if( vidOne == vidTwo ){
////						continue;
////					}
//					/**
//					 * Here the TripleWritable stores:
//					 * first:  Source Id of the message;
//					 * second: The source Id of edge to be checked;
//					 * third:  The target Id of edge to be checked.
//					 */
//					if(vidOne < vidTwo){
//						/**
//						 * NOTE: Do need to renew the message, it could use the same object.
//						 * One vertex one message object.
//						 */
//						msgStub.initialize(getId().get(), vidOne, vidTwo);
//						sendMessage(edgeOne.getTargetVertexId(), msgStub);
//						/*NOTE: Not balance distribution. */
////						int pid = vidOne % partitionNum;
////						count.put(pid, count.get(pid)+1);
//						
////						candidate[0] = vidOne;
////						candidate[1] = vidTwo;
////						dist.setDataVerticesSequence(candidate);
//						/*missing degree info*/
////						pid = dist.pickTargetId(2);
////						count2.put(pid , count2.get(pid)+1);
//						
//					}
//				}
//			}
//			aggregate(AGG_MAXMSG, new LongWritable(sendMsg));
//			  for(int i = 0; i < partitionNum; i++){
//				  aggregate(AGG_WORKLOADDIST+i, new LongWritable(count.get(i)));  
////				  aggregate(AGG_WORKLOADDIST2+i, new LongWritable(count2.get(i)));
//			  }
			  
			  
			voteToHalt();
		}
		else if(getSuperstep() == 1){
			/* check edge existence and report to the source of edge */
			long localTC = 0;
			for(TripleWritable msg : messages){
				for(Edge<IntWritable, NullWritable> edge : getEdges()){
					if(msg.getThird() == edge.getTargetVertexId().get()){
						localTC++;
						msgStub.initialize(msg.getSecond(), msg.getSecond(), msg.getThird());
						sendMessage(getId(), msgStub);
						
						msgStub.initialize(msg.getSecond(), msg.getFirst(), msg.getThird());
						targetStub.set(msg.getFirst());
						sendMessage(targetStub, msgStub);

						msgStub.initialize(msg.getSecond(), msg.getFirst(), msg.getSecond());
						sendMessage(targetStub, msgStub);
					}
				}
			}
			aggregate(AGG_TRIANGLE, new LongWritable(localTC));
		}
		else if(getSuperstep() == 2){
			/* count the triangle, remove invalid edge and report to neighbors*/
			count.clear();
			
			for(TripleWritable msg : messages){
//				System.out.println("Superstep="+getSuperstep()+" MSG: vid="+getId()+", "+msg.toString());
				int target = msg.getThird();
				if(count.get(target) == null){
					count.put(target, 1);
				}
				else{
					count.put(target, count.get(target) + 1);
				}
			}
			
			/* retrieve cut edges here */
			for(Edge<IntWritable, NullWritable> edgeOne : getEdges()){
				int target = edgeOne.getTargetVertexId().get();
				if(this.isCutEdge(getId().get(), target) == true){
					aggregate(AGG_MAXCUTTHETA, new LongWritable(count.get(target)==null?0:count.get(target)));
					aggregate(AGG_MINCUTTHETA, new LongWritable(count.get(target)==null?0:count.get(target)));
				}
				
			}
		}
		else{
			voteToHalt();
		}
	}
	
	public int getSupportValue(int vid){
		if(count.get(vid) == null){
			return 0;
		}
		return count.get(vid);
	}

	/** Master compute which uses aggregators. */
	  public static class AggregatorsMasterCompute extends
	      DefaultMasterCompute {
		  int partitionNum;
	    @Override
	    public void compute() {
	    		long totalTriangles = ((LongWritable)getAggregatedValue(AGG_TRIANGLE)).get();
	    		long maxcut =  ((LongWritable)getAggregatedValue(AGG_MAXCUTTHETA)).get();
	    		long mincut =  ((LongWritable)getAggregatedValue(AGG_MINCUTTHETA)).get();
	    		System.out.println("Triangle: "+totalTriangles+" MaxCut: "+maxcut+" MinCut: " +mincut);
	    }

	    @Override
	    public void initialize() throws InstantiationException,
	        IllegalAccessException {
	      registerAggregator(AGG_TRIANGLE, LongSumAggregator.class);
	      registerAggregator(AGG_MAXCUTTHETA, LongMaxAggregator.class);
	      registerAggregator(AGG_MINCUTTHETA, LongMinAggregator.class);
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
					StringBuilder neighbors = new StringBuilder();
					TriangleCount v = (TriangleCount)vertex;
//					System.out.println("vid="+v.getId());
					for(Edge<IntWritable, NullWritable> edge : vertex.getEdges()){
//						System.out.print(" (nid="+edge.getTargetVertexId()+
//								", "+v.getSupportValue(edge.getTargetVertexId().get())+") ");
						neighbors.append(v.getSupportValue(edge.getTargetVertexId().get()) +"\n");
					}
//					System.out.println();
					getRecordWriter().write(
						null,
						new Text(neighbors.toString()));
				}
		}
	}	
}
