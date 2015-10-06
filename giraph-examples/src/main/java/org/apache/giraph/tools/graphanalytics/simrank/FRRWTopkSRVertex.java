package org.apache.giraph.tools.graphanalytics.simrank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
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
import org.apache.giraph.tools.utils.PairWritable;
import org.apache.giraph.tools.utils.Random;

import com.google.common.collect.Lists;

@Algorithm(
		name = "All Pair Monte-Carlo based Random Walks for calculating top-k SimRank",
		description = "compute s(u,v) based on Sampled Random Walk with first-meeting gaurantee "+
		"(Refer to Scaling Link-Based Similarity Search, SIGMOD 2005)."
)
public class FRRWTopkSRVertex 
extends Vertex<IntWritable, IntWritable, NullWritable, PairWritable>{

		private static Logger LOG = Logger.getLogger(FRRWTopkSRVertex.class);
		private static double decayFactor = 0.6; // decayFactor = .6
		private int MAX_ITERATION_NUM = 11; // T =11
		private int RandomWalksNum = 100; // R=100
		
		private int queryVertex = 10;
//		private double threshold = 0.0;
		
		HashMap<Integer, HashSet<Integer>> count = new HashMap<Integer, HashSet<Integer>>();
		ArrayList<IntWritable> al; // using it to generate random neighbors.
		
		@Override
		public void compute(Iterable<PairWritable> messages)
				throws IOException {
			if(getSuperstep() > MAX_ITERATION_NUM){
				//aggregate here!
				voteToHalt();
				return ;
			}
			
			if(getSuperstep() == 0){
				/* initialization */
				MAX_ITERATION_NUM = this.getConf().getInt("simrank.maxiter", 11);
				RandomWalksNum = this.getConf().getInt("simrank.samplenum", 100);
				queryVertex = this.getConf().getInt("simrank.src", 10);
				
				for(int i = 0; i < RandomWalksNum; i++){
					if(this.getNumEdges() == 0){
						LOG.info("This vertex has no outgoing neighbors!");
						return ;
					}
					this.sendMessage(this.randomGetNeighbor(), new PairWritable(this.getId().get(), i));
				}
			}
			else{
				count.clear();
				for(PairWritable msg : messages){
					int tmpVid = msg.getFirst();
					if(count.get(tmpVid) == null){
						count.put(tmpVid, new HashSet<Integer>());
					}
					count.get(tmpVid).add(msg.getSecond());
				}
				HashSet<Integer> qvSet = count.get(queryVertex);
				if(qvSet != null){
					double factor = 1.0;
					long step = getSuperstep();
					for(int i = 1; i <= step; i++){
						factor *= decayFactor;
					}
					
					for(int vid : count.keySet()){
						if(vid != queryVertex){
							HashSet<Integer> counterVertex = count.get(vid);
							for(int sn : counterVertex){
								if(qvSet.contains(sn)){
									((SimRankWorkerContext)getWorkerContext()).add(vid, factor);
								}
								else{
									if(this.getNumEdges() > 0){
										this.sendMessage(this.randomGetNeighbor(), new PairWritable(vid, sn));
									}
								}
							}
						}
					}
					if(this.getNumEdges() > 0){
						for(int sn : qvSet){
							this.sendMessage(this.randomGetNeighbor(), new PairWritable(queryVertex, sn));
						}
					}
				}
				else if(this.getNumEdges() > 0){
					for(int vid : count.keySet()){
						HashSet<Integer> counterVertex = count.get(vid);
						for(int sn : counterVertex){
							this.sendMessage(this.randomGetNeighbor(), new PairWritable(vid, sn));
						}
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
			long randNum = Random.nextInt();
			randNum = (randNum < 0) ? -randNum : randNum;
			return al.get((int)(randNum % this.getNumEdges()));
		}

		/** Master compute which uses aggregators. */
		public static class AggregatorsMasterCompute extends
		      DefaultMasterCompute {
		    @Override
		    public void compute() {
		    	long step = this.getContext().getConfiguration().getInt("simrank.maxiter", 11);
		    	long sampleNum = this.getContext().getConfiguration().getInt("simrank.samplenum", 100);
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
		    			System.out.println(cnt+" ==> ("+ srp.getVertex()+", " + String.format("%.9f", srp.getSimRank()/sampleNum)+")");
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
		public static class FRRWTopkSRVertexInputFormat extends
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

		public static class FRRWTopkSRVertexOutputFormat extends
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