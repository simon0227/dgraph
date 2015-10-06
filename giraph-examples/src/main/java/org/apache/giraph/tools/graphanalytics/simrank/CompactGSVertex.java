package org.apache.giraph.tools.graphanalytics.simrank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

import org.apache.giraph.edge.Edge;
import org.apache.giraph.edge.EdgeFactory;
import org.apache.giraph.examples.Algorithm;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.formats.AdjacencyListTextVertexInputFormat;
import org.apache.giraph.io.formats.TextVertexOutputFormat;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.giraph.tools.utils.HashMapWritable;
import org.apache.giraph.tools.utils.PairWritable;
import org.apache.giraph.tools.utils.Random;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.google.common.collect.Lists;

@Algorithm(
		name = "Compact SimRank Solution based on graph sampling",
		description = "each sampled index generate a message."
)
public class CompactGSVertex 
extends Vertex<IntWritable, IntWritable, NullWritable, PathWritable>{

	private int numSampleGraph = 0;
	private int numQuerySample = 0;
	private int numIteration = 0;
	private int queryVid = 0;
	private double decayFactor = 0.6;
	ArrayList<IntWritable> neighbors = new ArrayList<IntWritable>();
	
	/* sample-respected data structure */
	private byte[] isVisited; /* sample-related */
	private byte[] hasIncomingVertex; /* sample-related */
	private int[] sampledNeighbors; /* sample-related */
	private HashMap<Integer, ArrayList<Short>> visitedTime; /* query-related vs time */
	
	@Override
	public void compute(Iterable<PathWritable> messages) throws IOException {
		if(getSuperstep() == 0){
			/* sampling the vertex and identifying no incoming-neighbor vertices. */
			numSampleGraph = getConf().getInt("simrank.samplenum", 100);
			numQuerySample = getConf().getInt("simrank.querysamplenum", 10);
			numIteration = getConf().getInt("simrank.maxiter", 11);
			queryVid = getConf().getInt("simrank.src", 0);//getConf().getInt("simrank.queryvertex", 0);
			decayFactor = (double)getConf().getFloat("simrank.decayfactor", 0.6f);
			
			hasIncomingVertex = new byte[(numSampleGraph >> 3) + 1];
			isVisited  = new byte[(numSampleGraph >> 3) + 1];
			sampledNeighbors = new int[numSampleGraph];
			visitedTime = new HashMap<Integer, ArrayList<Short>>();
			
			for(Edge<IntWritable, NullWritable> edge : getEdges()){
				neighbors.add(new IntWritable(edge.getTargetVertexId().get()));
			}
			if(neighbors.size() > 0){
				for(int i = 0; i < numSampleGraph; i++){
					isVisited[i>>3] &= 0;
					hasIncomingVertex[i>>3] &= 0;
					long rand = Math.abs(Random.nextInt());
					int idx = (int)(rand % neighbors.size());
					sampledNeighbors[i] = neighbors.get(idx).get();
					sendMessage(neighbors.get(idx), new PathWritable(i, -1) );
				}
			}
		}
		else if(getSuperstep() == 1){
			for(PathWritable msg : messages){
				int sid = msg.getSampleId();
				hasIncomingVertex[sid >> 3] |= (1 << (sid & 7));
			}
		}
		else if(getSuperstep() == 2){
			/* query phase. */
			if(getId().get() == queryVid){
				System.out.println("qid="+queryVid+" outNeighbors="+this.getNumEdges());
				if(this.getNumEdges() > 0){
					for(int k = 0; k < numSampleGraph; k++){
						for(int i = 0; i < numQuerySample; i++){
							sendMessage(randomGetNeighbor(), new PathWritable(k, -(queryVid + 1)));
						}
					}
				}
			}
		}
		else if(getSuperstep() == 3){
			for(int k = 0; k < numSampleGraph; k++){
				/*NOTE: cannot handle cycle index currently. */
				if((hasIncomingVertex[k>>3] & (1<<(k&7))) == 0){
					PathWritable msg = new PathWritable(k, numIteration + 1);
					msg.addVertex(getId().get(), (isVisited[k>>3]&(1<<(k&7))) == 0 ? numIteration : -1);
					isVisited[k>>3] |= (1<<(k&7));
					for(int idx = 0; idx < sampledNeighbors.length; idx++){
						sendMessage(new IntWritable(sampledNeighbors[idx]), msg);
					}
				}
			}
			for(PathWritable msg : messages){
				if(msg.isQueryMessage()){
					int sid = msg.getSampleId();
					if(visitedTime.get(sid) == null){
						visitedTime.put(sid, new ArrayList<Short>());
					}
					visitedTime.get(sid).add((short)(getSuperstep() - 2));
					
					/* query-related random walk message */
					if(getSuperstep() < 2 + numIteration){
						if(this.getNumEdges() > 0){
							sendMessage(randomGetNeighbor(), msg);
						}
					}
				}
				else{
					System.out.println("Error! Here all messages should be query-related.");
					System.exit(-1);
				}
			}
		}
		else{
			double delta = 0.0;
			/* Here we need to make sure the messages are ordered. */
			for(PathWritable msg : messages){
				int sid = msg.getSampleId();		
				if(msg.isQueryMessage()){
					if(visitedTime.get(sid) == null){
						visitedTime.put(sid, new ArrayList<Short>());
					}
					visitedTime.get(sid).add((short)(getSuperstep() - 2));
					
					/* query-related random walk message */
					if(getSuperstep() < 2 + numIteration){
						if(this.getNumEdges() > 0){
							sendMessage(randomGetNeighbor(), msg);
						}
					}
				}
				else {
					msg.decCount();
					msg.addVertex(getId().get(), (isVisited[sid>>3]&(1<<(sid&7))) == 0 ? numIteration : -1);
					isVisited[sid>>3] |= (1<<(sid&7));
					
					/* compute similarity here! */
					ArrayList<Short> vst = this.visitedTime.get(sid);
					if(vst != null){
						for(short vt : vst){
							int vid = msg.getMeetPoints(vt);
							if(vid >= 0){
								((SimRankWorkerContext)getWorkerContext()).add(vid, 
									Math.pow(decayFactor, vt) / numSampleGraph / numQuerySample);
							}
						}
					}
					
					if(msg.isFinished() == false){
						for(int idx = 0; idx < sampledNeighbors.length; idx++){
							sendMessage(new IntWritable(sampledNeighbors[idx]), msg);
						}
					}
				}
			}			
			voteToHalt();
		}
	}
	
	private IntWritable randomGetNeighbor(){
		if(neighbors == null){
			neighbors = new ArrayList<IntWritable>();
			for(Edge<IntWritable, NullWritable> edge : this.getEdges()){
				neighbors.add(new IntWritable(edge.getTargetVertexId().get()));
			}
		}
		long randNum = Random.nextInt();
		randNum = (randNum < 0) ? -randNum : randNum;
		return neighbors.get((int)(randNum % this.getNumEdges()));
	}
	
	/** Master compute which uses aggregators. */
	public static class AggregatorsMasterCompute extends
	      DefaultMasterCompute {
	    @Override
	    public void compute() {
	    	long step = this.getContext().getConfiguration().getInt("simrank.maxiter", 11);
	    	if(getSuperstep() == step + 1 ){//|| getSuperstep() == step + 1 || getSuperstep() == step - 1){
	    		int k = this.getContext().getConfiguration().getInt("simrank.topk", 10);
	    		HashMapWritable hm = ((HashMapWritable)getAggregatedValue("simrank.localagg"));
	    		
	    		PriorityQueue<SimRankPair> pq = new PriorityQueue<SimRankPair>();
	    		
	    		HashMap<Integer, Double> simranks = hm.getData();
	    		if(simranks == null){
	    			System.out.println("step " + getSuperstep()+": No Results......");
	    			return ;
	    		}
	    		
	    		for(int vid : simranks.keySet()){
	    			pq.add(new SimRankPair(vid, simranks.get(vid)));
	    			if(pq.size() > k)
	    				pq.remove(); // remove the smallest on
	    		}
	    		/* output the results. */
	    		int cnt = k;
	    		while(pq.isEmpty() == false){
	    			SimRankPair srp = pq.poll();
	    			System.out.println(cnt+" ==> ("+ srp.getVertex()+", " + String.format("%.9f", srp.getSimRank())+")");
	    			cnt--;
	    		}	
	    	}
	    }
	    
	    @Override
	    public void initialize() throws InstantiationException,
	        IllegalAccessException {
//	      registerAggregator("aggregate.pairsimrank", DoubleSumAggregator.class);
//	      registerAggregator("aggregate.tmppairsimrank", DoubleSumAggregator.class);
	    	registerAggregator("simrank.localagg", HashMapWritableAggregator.class);
	    }
	}
	
	/** Vertex InputFormat */
	public static class CompactGSVertexInputFormat extends
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
					return new IntWritable(0);//decodeValue(null);
				}

				@Override
				protected Iterable<Edge<IntWritable, NullWritable>> getEdges(String[] values) throws
				IOException {
					int i = 1;
					List<Edge<IntWritable, NullWritable>> edges = Lists.newLinkedList();
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

	public static class CompactGSVertexOutputFormat extends
		TextVertexOutputFormat<IntWritable, IntWritable, NullWritable> {
			@Override
			public TextVertexWriter createVertexWriter(TaskAttemptContext context)
			throws IOException, InterruptedException {
			return new OrderedGraphWriter();
			}
			
			public class OrderedGraphWriter extends TextVertexWriter {
				@Override
				public void writeVertex(
						Vertex<IntWritable, IntWritable, NullWritable, ?> vertex)
				throws IOException, InterruptedException {
					getRecordWriter().write(
						new Text(vertex.getId().toString()),
						new Text(vertex.getValue().toString()));
				}
		}
	}

}
