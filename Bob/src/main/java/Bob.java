import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;

import java.io.File;
import java.io.FileReader;
import java.math.BigInteger;
import java.util.*;


public class Bob extends AbstractBehavior<Message> {

	private ActorRef<Message> alice;
	private AlgSelection.Algorithm alg;
	private DBSelection.DB db_name;
	private List<int[]> db = new ArrayList<>();
	private int alice_db_size;
	Double min_supp;
	public static final ServiceKey<Message> sk = ServiceKey.create(Message.class, "Bob");

	private Encryptor encryptor = null;
	private HashMap<Set<Integer>,BigInteger[]> x_vectors = new HashMap<>();

	int [][] A;
	HashMap<List<Integer>,Integer> tempA = new HashMap<>();

	static Behavior<Message> create() {
		return Behaviors.setup(Bob::new);
	}

	private Bob(ActorContext<Message> context) {
		super(context);
		context.getSystem().receptionist().tell(Receptionist.register(sk, context.getSelf()));
		alice = null;
	}

	public Behavior<Message> shutdown(ShutDown cmd){
		return Behaviors.stopped();
	}

	 @Override
	 public Receive<Message> createReceive() {
	        return newReceiveBuilder()
					.onMessage(DBSelection.class, this::db_selection)
					.onMessage(AlgSelection.class,this::alg_selection)
					.onMessage(MinSuppSelection.class,this::min_sup_selection)
					.onMessage(StartApriori.class, this::generate_f1_Bob)
					.onMessage(ShutDown.class,this::shutdown)
					.onMessage(Freq.class,this::count_freq)
					.onMessage(XVector.class,this::assemble_x_vector)
					.onMessage(CountFreqH.class,this::count_freq_homomorphic)
					.onMessage(VCAmatrix.class, this::populateAMatrix)
					.onMessage(CountFreqVC.class, this::count_freq_vc)
					.build();
	 }

	////////////////////////////// Apriori Gen //////////////////////////////////////////////////////

	/**
	 * initialization of Apriori algorithm - finding all frequent-1 ItemSets in Bob's share of the DB.
	 * once found - they are sent to Alice
	 */
	protected Behavior<Message> generate_f1_Bob (StartApriori cmd){
		HashMap<Integer,Integer> f1 = new HashMap<>();
		int[] counters = new int[db.get(0).length];
		for (int[] record : db) {
			for(int i = 1; i<record.length;i++){
				counters[i] += record[i];
			}
		}
		for (int i = 1;i<counters.length;i++) {
			double c = ((double)counters[i]) / ((double) db.size());
			if(c>min_supp){
				f1.put(col_index_to_alice(i),counters[i]);
			}
		}
		alice.tell(new StartApriori(null,f1,getContext().getSelf()));
		return this;
	}

	/**
	 * count the frequency of the itemset given as input and send alice the result
	 * @param f - contains the itemset to be counted
	 */
	protected Behavior<Message> count_freq (Freq f){
		Set<Integer> bob_cols = col_indices_to_bob(f.local);
		int result = VectorOps.sum_vector(VectorOps.generate_x_vector(bob_cols,db));
		alice.tell(new FreqResponse(result,f.local,getContext().getSelf()));
		return this;
	}

	/**
	 * translate an index in the global database to the one in Bob's share of the database
	 * @param col - the index to be translated
	 * @return - the new index
	 */
	private int col_index_to_bob(int col){
		return col - alice_db_size + 1;
	}

	/**
	 * translate a set of index in the global database to the one in Bob's share of the database
	 * @param s - the index set to be translated
	 * @return - the new index
	 */
	private Set<Integer> col_indices_to_bob (Set<Integer> s){
		Set<Integer> output = new TreeSet<>();
		for (Integer i : s) {
			output.add(col_index_to_bob(i));
		}
		return  output;
	}

	/**
	 * translate an index in bob's share of the database to global version used by alice
	 * @param col - the index to be translated
	 * @return - the new index
	 */
	private int col_index_to_alice(int col){
		return col + alice_db_size - 1;
	}



	////////////////////////////////  Privacy Preserving Communication  /////////////////////////////////////

	/**
	 * populate local copy of A matrix based on seed received from Alice
	 * @param msg - contains an entry in the A matrix generated by Alice
	 */
	protected Behavior<Message> populateAMatrix(VCAmatrix msg) {
		A = generate_A_Matrix(msg.seed);
		return this;
	}



	/**
	 * Take an entry in an x vector sent by alice and place it at the appropriate place
	 * (based on index and the key given by the itemsets recieved from alice) in the x_vector Hashmap.
	 * Creates a new vector in the Hashmap if needed.
	 * Once an entire vector has been filled (since message order is guaranteed by akka, this happens when we reach the final index)
	 * send a message to initiate the counting process: {@link #count_freq_homomorphic} or {@link #count_freq_vc}
	 * @param xVector - contains an entry in the x vector for the given ItemSet at given index
	 */
	protected Behavior<Message> assemble_x_vector (XVector xVector){
		xVector.non_local.addAll(xVector.local);
		if(xVector.index == 0){
			BigInteger [] v = new BigInteger[db.size()];
			x_vectors.put(xVector.non_local,v);
		}

		x_vectors.get(xVector.non_local)[xVector.index] = xVector.xi;

		if(xVector.index == db.size()-1){
			if(alg == AlgSelection.Algorithm.HOMOMORPHIC)
				getContext().getSelf().tell(new CountFreqH(xVector.local,xVector.non_local));
			else
				getContext().getSelf().tell(new CountFreqVC(xVector.local,xVector.non_local));
		}
		return this;
	}


	/**
	 * generate a new Y' vector and send it to alice.
	 * Then, generate a result S representing the Dot product of the x vector sent by Alice with the specified local y vector.
	 * when done send the result back to alice
	 * @param vc - contains the itemset currently being examined
	 */
	protected Behavior<Message> count_freq_vc (CountFreqVC vc){
		BigInteger[] x = x_vectors.get(vc.combined_set);
		Set<Integer> bob_cols = col_indices_to_bob(vc.local);
		int [] y = VectorOps.generate_x_vector(bob_cols,db);

		for(int j = 0 ; j<y.length/2 ; j++){
			BigInteger yi = new BigInteger("0");
			for(int i = 0; i<y.length; i++){
				yi = yi.add(new BigInteger(Integer.toString(y[i])).multiply(new BigInteger(Integer.toString(A[i][j]))));
			}
			alice.tell(new VCytag(yi,j,vc.combined_set,getContext().getSelf()));
		}

		BigInteger S = new BigInteger("0");

		for(int i = 0; i<y.length; i++){
			BigInteger yi = new BigInteger(Integer.toString(y[i]));
			S = S.add(x[i].multiply(yi));
		}

		x_vectors.remove(vc.combined_set);
		alice.tell(new VCS(S,vc.combined_set,getContext().getSelf()));
		return this;
	}


	/**
	 * generate an encrypted result representing the Dot product of the x vector sent by Alice with the specified local y vector.
	 * when done send the result back to alice
	 * @param h - contains the itemset currently being examined
	 */
	protected Behavior<Message> count_freq_homomorphic (CountFreqH h){
		BigInteger[] x = x_vectors.get(h.combined_set);
		Set<Integer> bob_cols = col_indices_to_bob(h.local);
		int [] y = VectorOps.generate_x_vector(bob_cols,db);
		BigInteger w = new BigInteger("1");

		for(int i = 0; i<y.length; i++){
			BigInteger yi = new BigInteger(Integer.toString(y[i]));
			BigInteger wi = x[i].modPow(yi,encryptor.nsquare);
			w = w.multiply(wi).mod(encryptor.nsquare);
		}

		x_vectors.remove(h.combined_set);
		alice.tell(new HomomorphicResponse(w,h.combined_set,getContext().getSelf()));
		return this;
	}



	////////////////////////////////  Message Handlers /////////////////////////////////////////////////////

	private Behavior<Message> alg_selection (AlgSelection msg){
		alg = msg.algorithm;
		if(alg == AlgSelection.Algorithm.HOMOMORPHIC){
			encryptor = msg.encryptor;
		}
		return this;
	}

	private Behavior<Message> min_sup_selection(MinSuppSelection msg){
		min_supp = msg.min_supp;
		getContext().getSelf().tell(new StartApriori(null,null,null));
		return this;
	}

	//////////////////////////////// CSV Processing ///////////////////////////////////////////////////////

	private Behavior<Message>  db_selection (DBSelection cmd){
		alice = cmd.replyTo;
		db_name = cmd.db;
		if(db_name.equals(DBSelection.DB.CMC)){
			alice_db_size = cmd.alice_db_sizes.get(DBSelection.DB.CMC);
			load_db("cmcBob.csv");
		}
		else{
			alice_db_size = cmd.alice_db_sizes.get(DBSelection.DB.HD);
			load_db("heartDiseaseDataBob.csv");
		}
		return this;
	}

	private void load_db(String path){
		try {
			ClassLoader classLoader = ClassLoader.getSystemClassLoader();
			File db_file = new File(classLoader.getResource(path).getFile());
			FileReader filereader = new FileReader(db_file);
			CSVReader csvReader = new CSVReaderBuilder(filereader).withSkipLines(1).build();
			List<String[]> db_as_string =  csvReader.readAll();
			for (String[] record: db_as_string) {
				db.add(Arrays.stream(record).mapToInt(Integer::parseInt).toArray());
			}
		}
		catch(Exception e){
			System.out.println(e.getMessage());
		}
	}

	/////////////////////////////// Internal Message Types ///////////////////////////////////////////////

	private class CountFreqH extends Message {
		private Set<Integer> local;
		private Set<Integer> combined_set;
		private CountFreqH(Set<Integer> local,Set<Integer> combined_set) {
			super(null,getContext().getSelf());
			this.local = local;
			this.combined_set = combined_set;
		}
	}

	private class CountFreqVC extends Message {
		private Set<Integer> local;
		private Set<Integer> combined_set;
		private CountFreqVC(Set<Integer> local,Set<Integer> combined_set) {
			super(null,getContext().getSelf());
			this.local = local;
			this.combined_set = combined_set;
		}
	}

	private int [][] generate_A_Matrix(long seed){
		int [][]rand_a = new int[db.size()][db.size()/2];
		Random rand = new Random(seed);

		for(int i = 0 ;i < db.size(); i++){
			for(int j = 0; j<db.size()/2; j++){
				rand_a[i][j] = rand.nextInt();
			}
		}

		int[][] a_matrix = new int[db.size()][db.size()/2];
		for(int i = 0 ;i < db.size(); i++){
			for(int j = 0; j<db.size()/2; j++){
				if(i == j){ //diagonal
					int new_diagonal = 0;
					for(int k = 0; k<db.size()/2; k++){
						new_diagonal += Math.abs(rand_a[i][k]);
					}
					a_matrix[i][j] = new_diagonal;
				}
				else a_matrix[i][j] = rand_a[i][j];
			}
		}

		return a_matrix;
	}

}

