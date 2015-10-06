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
		name = "Basic SimRank Solution based on graph sampling",
		description = "each sampled index generate a message."
)
public class BasicGSVertex 
extends Vertex<IntWritable, IntWritable, NullWritable, PairWritable>{

	private int numSampleGraph = 0;
	private int numQuerySample = 0;
	private int numIteration = 0;
	private int queryVid = 0;
	private double decayFactor = 0.6;
	ArrayList<IntWritable> neighbors = new ArrayList<IntWritable>();
	HashMap<Integer, ArrayList<Integer>> reversedSampleIndex;
	
	@Override
	public void compute(Iterable<PairWritable> messages) throws IOException {
		if(getSuperstep() == 0){
			/* sampling the vertex and constructing the (reversed) index. */
			numSampleGraph = getConf().getInt("simrank.samplenum", 100);
			numQuerySample = getConf().getInt("simrank.querysamplenum", 10);
			numIteration = getConf().getInt("simrank.maxiter", 11);
			queryVid = getConf().getInt("simrank.src", 0);//getConf().getInt("simrank.queryvertex", 0);
			decayFactor = (double)getConf().getFloat("simrank.decayfactor", 0.6f);
			
			for(Edge<IntWritable, NullWritable> edge : getEdges()){
				neighbors.add(new IntWritable(edge.getTargetVertexId().get()));
			}
			if(neighbors.size() > 0){
				for(int i = 0; i < numSampleGraph; i++){
					long rand = Math.abs(Random.nextInt());
					int idx = (int)(rand % neighbors.size());
					sendMessage(neighbors.get(idx), new PairWritable(i, neighbors.get(idx).get()) );
				}
			}
		}
		else if(getSuperstep() == 1){
			/* materialize the reversed numSampleGraph index (single outdegree tree) */
			reversedSampleIndex = new HashMap<Integer, ArrayList<Integer>>();
			for(PairWritable msg : messages){
				int sid = msg.getFirst();
				int did = msg.getSecond();
				if(reversedSampleIndex.get(sid) == null){
					reversedSampleIndex.put(sid, new ArrayList<Integer>());
				}
				reversedSampleIndex.get(sid).add(did);
			}
		}
		else if(getSuperstep() == 2){
			/* query phase. */
			if(getId().get() == queryVid){
				System.out.println("qid="+queryVid+" outNeighbors="+this.getNumEdges());
				if(this.getNumEdges() > 0){
					for(int k = 0; k < numSampleGraph; k++){
						for(int i = 0; i < numQuerySample; i++){
							sendMessage(randomGetNeighbor(), new PairWritable(k, queryVid+1));
						}
					}
				}
			}
			voteToHalt();
		}
		else{
			double delta = 0.0;
			for(PairWritable msg : messages){
				if(msg.getSecond() > 0){
					/* query-related random walk message */
					if(getSuperstep() < 2 + numIteration){
						if(this.getNumEdges() > 0){
							sendMessage(randomGetNeighbor(), msg);
						}
					}
					ArrayList<Integer> reversedSampleNeighbors = this.reversedSampleIndex.get(msg.getFirst());
					if(reversedSampleNeighbors != null){
						for(int nid : reversedSampleNeighbors){
							sendMessage(new IntWritable(nid), new PairWritable(msg.getFirst(), (int)(3 - getSuperstep())));
						}
					}
				}
				else if(msg.getSecond() == 0){
					/* find meeting points here. */
					if(getSuperstep() % 2 != 0){
						System.out.println("Error!!!!!!!!! superstep=" + getSuperstep());
						System.exit(0);
					}
					int step = (int)(getSuperstep() - 2) / 2;
					delta += Math.pow(decayFactor, step);
				}
				else{
					ArrayList<Integer> reversedSampleNeighbors = this.reversedSampleIndex.get(msg.getFirst());
					if(reversedSampleNeighbors != null){
						for(int nid : reversedSampleNeighbors){
							sendMessage(new IntWritable(nid), new PairWritable(msg.getFirst(), msg.getSecond() + 1));
						}
					}
				}
			}
			
			if(delta > 0.0 && this.getId().get() != queryVid){
				((SimRankWorkerContext)getWorkerContext()).add(this.getId().get(), delta / numSampleGraph / numQuerySample);
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
	public static class BasicGSVertexInputFormat extends
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

	public static class BasicGSVertexOutputFormat extends
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
