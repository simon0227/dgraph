package org.apache.giraph.tools.graphanalytics.simrank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

import org.apache.giraph.aggregators.DoubleSumAggregator;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.edge.EdgeFactory;
import org.apache.giraph.examples.Algorithm;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.formats.AdjacencyListTextVertexInputFormat;
import org.apache.giraph.io.formats.TextVertexOutputFormat;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.log4j.Logger;
import org.apache.giraph.tools.utils.HashMapWritable;
import org.apache.giraph.tools.utils.Random;

import com.google.common.collect.Lists;

@Algorithm(
		name = "All Pair Random Walks for calculating top-k SimRank",
		description = "compute s(u,v) based on all Random Walks without first-meeting gaurantee "+
		"(Refer to Scalable Similarity Search for SimRank, SIGMOD 2014)."
)
public class ARWTopkSRVertex 
extends Vertex<IntWritable, IntWritable, NullWritable, ProbPairWritable>{

		private static Logger LOG = Logger.getLogger(ARWTopkSRVertex.class);
		private static double decayFactor = 0.6; // decayFactor = .6
		private int MAX_ITERATION_NUM = 11; // T =11
//		private int RandomWalksNum = 100; // R=100
		
		private int queryVertex = 10;
		private double threshold = 0.0;
		
		HashMap<Integer, Double> count = new HashMap<Integer, Double>();
		boolean isQvArrived = false;
		ArrayList<IntWritable> al; // using it to generate random neighbors.
		
		@Override
		public void compute(Iterable<ProbPairWritable> messages)
				throws IOException {
			if(getSuperstep() > MAX_ITERATION_NUM){
				//aggregate here!
				voteToHalt();
				return ;
			}
			
			if(getSuperstep() == 0){
				/* initialization */
				MAX_ITERATION_NUM = this.getConf().getInt("simrank.maxiter", 11);
				queryVertex = this.getConf().getInt("simrank.src", 10);
				if(getNumEdges() > 0)
					sendMessageToAllEdges(new ProbPairWritable(this.getId().get(), 1.0/this.getNumEdges()));
			}
			else{
				isQvArrived = false;
				count.clear();
				double qvProb = 0;
				for(ProbPairWritable msg : messages){
					int tmpVid = msg.getVertex();
					if(tmpVid == queryVertex){
						isQvArrived = true;
						qvProb += msg.getprob();
						continue;
					}
					Double cur = count.get(tmpVid);
					if(cur == null){
						cur = 0.0;
					}
					count.put(tmpVid, cur+msg.getprob());
				}
				
				if(isQvArrived){
					long step = getSuperstep();
					double factor = 1.0;
					for(int i = 1; i <= step; i++){
						factor *= decayFactor;
					}
					for(int vid : count.keySet()){
						/* tmp simrank for (queryVertex, vid) */
						double deltaSimRank = factor * qvProb * count.get(vid);
						if(deltaSimRank > threshold){
							((SimRankWorkerContext)getWorkerContext()).add(vid, deltaSimRank);
						}
					}
					if(this.getNumEdges() > 0 && qvProb > threshold)
						this.sendMessageToAllEdges(new ProbPairWritable(queryVertex, qvProb / this.getNumEdges()));
				}

				if(this.getNumEdges() > 0){
					for(int vid : count.keySet()){
						if( count.get(vid) > threshold)
							this.sendMessageToAllEdges(new ProbPairWritable(vid, count.get(vid) / this.getNumEdges()));
					}
				}
				count.clear();
			}
			voteToHalt();
		}
		
		
		private IntWritable randomGetNeighbor(){
			if(al == null){
				al = new ArrayList<IntWritable>();
				for(Edge<IntWritable, NullWritable> edge : this.getEdges()){
					al.add(new IntWritable(edge.getTargetVertexId().get()));
				}
			}
			int randNum = Math.abs(Random.nextInt());
			return al.get(randNum % this.getNumEdges());
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
//		      registerAggregator("aggregate.pairsimrank", DoubleSumAggregator.class);
//		      registerAggregator("aggregate.tmppairsimrank", DoubleSumAggregator.class);
		    	registerAggregator("simrank.localagg", HashMapWritableAggregator.class);
		    }
		  }
		  
		/** Vertex InputFormat */
		public static class ARWTopkSRVertexInputFormat extends
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

		public static class ARWTopkSRVertexOutputFormat extends
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