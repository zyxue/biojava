package org.biojava.nbio.structure.symmetry.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.vecmath.Matrix4d;

import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.Calc;
import org.biojava.nbio.structure.StructureException;
import org.biojava.nbio.structure.StructureTools;
import org.biojava.nbio.structure.align.AbstractStructureAlignment;
import org.biojava.nbio.structure.align.MultipleStructureAligner;
import org.biojava.nbio.structure.align.ce.CECalculator;
import org.biojava.nbio.structure.align.ce.CeCPMain;
import org.biojava.nbio.structure.align.ce.ConfigStrucAligParams;
import org.biojava.nbio.structure.align.ce.MatrixListener;
import org.biojava.nbio.structure.align.model.AFPChain;
import org.biojava.nbio.structure.align.multiple.MultipleAlignment;
import org.biojava.nbio.structure.align.multiple.MultipleAlignmentEnsemble;
import org.biojava.nbio.structure.align.multiple.MultipleAlignmentEnsembleImpl;
import org.biojava.nbio.structure.align.multiple.util.MultipleAlignmentScorer;
import org.biojava.nbio.structure.align.util.AFPChainScorer;
import org.biojava.nbio.structure.align.util.AtomCache;
import org.biojava.nbio.structure.align.util.RotationAxis;
import org.biojava.nbio.structure.jama.Matrix;
import org.biojava.nbio.structure.symmetry.internal.CESymmParameters.RefineMethod;
import org.biojava.nbio.structure.symmetry.internal.CESymmParameters.SymmetryType;
import org.biojava.nbio.structure.symmetry.utils.SymmetryTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identify the symmetries in a structure by running an alignment of 
 * the structure against itself disabling the diagonal of the identity 
 * alignment.
 * <p>
 * Iterating recursively over all results and disabling the diagonal 
 * of each previous result can also be done with this implementation,
 * which will generate a set of self-alignments.
 * <p>
 * The alignment is then refined to obtain a consistent alignment among
 * all residues of the structure and organized into different parts,
 * called symmetric subunits. Optionally, after refinement of the initial
 * alignment, an optimization step can be used to improve the overall
 * score of the subunit alignment (multiple alignment).
 * 
 * @author Andreas Prlic
 * @author Spencer Bliven
 * @author Aleix Lafita
 * 
 */
public class CeSymm extends AbstractStructureAlignment 
implements MatrixListener, MultipleStructureAligner {

	/**
	 * Version History:<p>
	 * <li>1.0 - initial implementation of CeSymm.
	 * <li>1.1 - enable multiple CeSymm runs to calculate all self-alignments.
	 * <li>2.0 - refine the alignment for consistency of subunit definition.
	 * <li>2.1 - optimize the alignment to improve the score.
	 */
	public static final String version = "2.1";
	public static final String algorithmName = "jCE-symmetry";
	private static final Logger logger = LoggerFactory.getLogger(CeSymm.class);

	private AFPChain afpChain;
	private MultipleAlignment msa;
	private List<AFPChain> afpAlignments;
	private SymmetryType type;
	private SymmetryAxes axes;
	private boolean refined;

	private Atom[] ca1;
	private Atom[] ca2;
	private int rows;
	private int cols;

	private CECalculator calculator;
	private CESymmParameters params;

	public CeSymm() {
		super();
		params = new CESymmParameters();
		refined = false;
	}

	public static void main(String[] args) {
		// Responsible for creating a CeSymm instance
		CeSymmUserArgumentProcessor processor = 
				new CeSymmUserArgumentProcessor();
		processor.process(args);
	}

	public static String toDBSearchResult(AFPChain afpChain) {
		StringBuffer str = new StringBuffer();

		str.append(afpChain.getName1());
		str.append("\t");
		str.append(afpChain.getName2());
		str.append("\t");
		str.append(String.format("%.2f", afpChain.getAlignScore()));
		str.append("\t");
		str.append(String.format("%.2f", afpChain.getProbability()));
		str.append("\t");
		str.append(String.format("%.2f", afpChain.getTotalRmsdOpt()));
		str.append("\t");
		str.append(afpChain.getCa1Length());
		str.append("\t");
		str.append(afpChain.getCa2Length());
		str.append("\t");
		str.append(afpChain.getCoverage1());
		str.append("\t");
		str.append(afpChain.getCoverage2());
		str.append("\t");
		str.append(String.format("%.2f", afpChain.getTMScore()));
		str.append("\t");
		str.append(afpChain.getOptLength());
		return str.toString();
	}

	public AFPChain indentifyAllSymmetries(String name1, String name2,
			AtomCache cache, int fragmentLength) throws StructureException,
			IOException {

		params = new CESymmParameters();

		params.setWinSize(fragmentLength);

		Atom[] ca1 = cache.getAtoms(name1);
		Atom[] ca2 = cache.getAtoms(name2);

		return align(ca1, ca2, params);
	}

	private static Matrix align(AFPChain afpChain, Atom[] ca1, Atom[] ca2, 
			CESymmParameters params, Matrix origM, CECalculator calculator, 
			int counter) throws StructureException {

		int fragmentLength = params.getWinSize();
		Atom[] ca2clone = StructureTools.cloneAtomArray(ca2);

		int rows = ca1.length;
		int cols = ca2.length;

		// Matrix that tracks similarity of a fragment of length fragmentLength
		// starting a position i,j.

		int blankWindowSize = fragmentLength;
		if (origM == null) {
			// Build alignment ca1 to ca2-ca2

			afpChain = calculator.extractFragments(afpChain, ca1, ca2clone);

			origM = SymmetryTools.blankOutPreviousAlignment(afpChain, ca2,
					rows, cols, calculator, null, blankWindowSize);

		} else {
			// we are doing an iteration on a previous alignment
			// mask the previous alignment
			origM = SymmetryTools.blankOutPreviousAlignment(afpChain, ca2,
					rows, cols, calculator, origM, blankWindowSize);
		}

		Matrix clone = (Matrix) origM.clone();

		// that's the matrix to run the alignment on..
		calculator.setMatMatrix(clone.getArray());

		calculator.traceFragmentMatrix(afpChain, ca1, ca2clone);

		final Matrix origMfinal = (Matrix) origM.clone();
		//Add a matrix listener to keep the blacked zones in max.
		calculator.addMatrixListener(new MatrixListener() {

			@Override
			public double[][] matrixInOptimizer(double[][] max) {

				//Check every entry of origM for blacked out regions
				for (int i=0; i<max.length; i++){
					for (int j=0; j<max[i].length; j++){
						if (origMfinal.getArray()[i][j]>1e9){
							max[i][j] = -origMfinal.getArray()[i][j];
						}
					}
				}
				return max;
			}

			@Override
			public boolean[][] initializeBreakFlag(boolean[][] brkFlag) {

				return brkFlag;
			}
		});

		calculator.nextStep(afpChain, ca1, ca2clone);

		afpChain.setAlgorithmName(algorithmName);
		afpChain.setVersion(version);

		afpChain.setDistanceMatrix(origM);

		return origMfinal;

	}

	@Override
	public double[][] matrixInOptimizer(double[][] max) {

		return CECalculator.updateMatrixWithSequenceConservation(max, ca1, ca2,
				params);
	}

	@Override
	public boolean[][] initializeBreakFlag(boolean[][] breakFlag) {
		int fragmentLength = params.getWinSize();
		try {
			if (afpChain != null) {
				breakFlag = SymmetryTools.blankOutBreakFlag(afpChain, ca2,
						rows, cols, calculator, breakFlag, fragmentLength);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return breakFlag;

	}

	public CECalculator getCalculator() {
		return calculator;
	}

	public void setCalculator(CECalculator calculator) {
		this.calculator = calculator;
	}

	@Deprecated
	@Override
	public AFPChain align(Atom[] ca1, Atom[] ca2) throws StructureException {

		if (params == null)	params = new CESymmParameters();
		return align(ca1, ca2, params);
	}

	@Deprecated
	@Override
	public AFPChain align(Atom[] ca10, Atom[] ca2O, Object param) 
			throws StructureException {

		//STEP 0: prepare all the information for the symmetry alignment
		if (!(params instanceof CESymmParameters))
			throw new IllegalArgumentException("CE-Symm algorithm needs an "
					+ "object of call CESymmParameters as argument.");

		this.params = (CESymmParameters) param;

		ca1 = ca10;
		ca2 = StructureTools.duplicateCA2(ca2O);
		rows = ca1.length;
		cols = ca2.length;

		if( rows == 0 || cols == 0) {
			throw new StructureException("Aligning empty structure");
		}

		Matrix origM = null;
		AFPChain myAFP = new AFPChain();
		afpAlignments = new ArrayList<AFPChain>();

		calculator = new CECalculator(params);
		calculator.addMatrixListener(this);

		//Set multiple to true if multiple alignments are needed for refinement
		boolean multiple = (params.getRefineMethod() == RefineMethod.MULTIPLE);
		Matrix lastMatrix = null;

		//STEP 1: perform the self-alignments of the structure with CECP
		int i = 0;
		do {

			if (origM != null) {
				myAFP.setDistanceMatrix((Matrix) origM.clone());
			}
			origM = align(myAFP, ca1, ca2, params, origM, calculator, i);

			double tmScore2 = AFPChainScorer.getTMScore(myAFP, ca1, ca2);
			myAFP.setTMScore(tmScore2);

			//Clone the AFPChain
			AFPChain newAFP = (AFPChain) myAFP.clone();

			//Post process the alignment
			newAFP = CeCPMain.postProcessAlignment(newAFP, ca1, ca2, calculator);

			//Calculate and set the TM score for the newAFP alignment
			double tmScore3 = AFPChainScorer.getTMScore(newAFP, ca1, ca2);
			newAFP.setTMScore(tmScore3);
			logger.debug("Alignment "+(i+1)+" score: "+newAFP.getTMScore());
			//Determine if the alignment is significant, stop if true
			if (tmScore3 < params.getSymmetryThreshold()){
				logger.debug("Not symmetric alignment with TM score: "
						+ newAFP.getTMScore());
				//If it is the first alignment save it anyway
				if (i==0) afpAlignments.add(newAFP);
				//store final matrix & 
				lastMatrix = newAFP.getDistanceMatrix().copy();
				break;
			}
			//If it is a symmetric alignment add it to the allAlignments list
			afpAlignments.add(newAFP);

			i++;

		} while (i < params.getMaxSymmOrder() && multiple);

		if(lastMatrix == null && afpAlignments.size()>1 ) {
			//We reached the maximum order, so blank out the final alignment
			AFPChain last = afpAlignments.get( afpAlignments.size()-1 );
			lastMatrix = SymmetryTools.blankOutPreviousAlignment(
					last,ca2, last.getCa1Length(), last.getCa2Length(), 
					calculator, origM, params.getWinSize());
			lastMatrix = lastMatrix.getMatrix(0, last.getCa1Length()-1, 
					0, last.getCa2Length()-1);
		}

		//Save the results to the CeSymm member variables
		afpChain = afpAlignments.get(0);
		String name = ca1[0].getGroup().getChain().getParent().getIdentifier();
		afpChain.setName1(name);
		afpChain.setName2(name);

		//Determine the symmetry Type or get the one in params
		type = params.getSymmetryType();
		if (type == SymmetryType.AUTO){
			if (afpChain.getBlockNum() == 1) type = SymmetryType.OPEN;
			else type = SymmetryType.CLOSE;
		}

		//STEP 2: calculate the order of symmetry for CLOSED symmetry
		int order = 1;
		OrderDetector orderDetector = null;
		switch (params.getOrderDetectorMethod()) {
		case SEQUENCE_FUNCTION: 
			orderDetector = new SequenceFunctionOrderDetector(
					params.getMaxSymmOrder(), 0.4f);
			break;
		}
		try {
			order = orderDetector.calculateOrder(afpChain, ca1);
		} catch (OrderDetectionFailedException e) {
			e.printStackTrace();
		}

		if (params.getRefineMethod() == RefineMethod.NOT_REFINED) 
			return afpChain;

		//STEP 3: symmetry refinement, apply consistency in the subunit residues		
		Refiner refiner = null;
		try {
			switch (type){
			case CLOSE:
				if (order == 1) return afpChain;
				refiner = new SingleRefiner();
				break;
			default: //case OPEN
				refiner = new OpenRefiner();
				break;
			}

			afpChain = refiner.refine(afpAlignments, ca1, order);
			refined = true;

		} catch (RefinerFailedException e) {
			logger.warn("Could not refine structure!");
			return afpChain;
		}

		//STEP4: determine the symmetry axis and its subunit dependencies
		order = afpChain.getBlockNum();
		axes = new SymmetryAxes();
		Matrix rot = afpChain.getBlockRotationMatrix()[0];
		Atom shift = afpChain.getBlockShiftVector()[0];
		Matrix4d axis = Calc.getTransformation(rot, shift);

		List<List<Integer>> superposition = new ArrayList<List<Integer>>();
		List<Integer> chain1 = new ArrayList<Integer>();
		List<Integer> chain2 = new ArrayList<Integer>();
		superposition.add(chain1);
		superposition.add(chain2);
		List<Integer> subunitTrans = new ArrayList<Integer>();

		switch(type){
		case CLOSE:

			for (int bk=0; bk<order; bk++){
				chain1.add(bk);
				chain2.add((bk+1)%order);
				subunitTrans.add(bk);
			}
			axes.addAxis(axis, superposition, subunitTrans, order);
			break;

		default: //case OPEN:

			subunitTrans.add(0);
			for (int bk=0; bk<order-1; bk++){
				chain1.add(bk);
				chain2.add(bk+1);
				subunitTrans.add(bk+1);
			}
			axes.addAxis(axis, superposition, subunitTrans, order);

			break;
		}

		return afpChain;
	}

	@Override
	public ConfigStrucAligParams getParameters() {
		return params;
	}

	@Override
	public void setParameters(ConfigStrucAligParams parameters) {
		if (!(parameters instanceof CESymmParameters)) {
			throw new IllegalArgumentException(
					"Need to provide CESymmParameters, but provided "
							+ parameters.getClass().getName());
		}
		params = (CESymmParameters) parameters;
	}

	@Override
	public String getAlgorithmName() {
		return algorithmName;
	}

	@Override
	public String getVersion() {
		return version;
	}

	public static boolean isSignificant(AFPChain afpChain, Atom[] ca1, 
			double symmetryThreshold) throws StructureException {

		// TM-score cutoff
		if (afpChain.getTMScore() < symmetryThreshold) return false;

		// sequence-function order cutoff
		int order = 1;
		try {
			OrderDetector orderDetector = 
					new SequenceFunctionOrderDetector(8, 0.4f);
			order = orderDetector.calculateOrder(afpChain, ca1);
		} catch (OrderDetectionFailedException e) {
			e.printStackTrace();
			// try another method
		}

		if (order > 1) return true;

		// angle order cutoff
		RotationAxis rot = new RotationAxis(afpChain);
		order = rot.guessOrderFromAngle(Math.toRadians(1.0), 8);
		if (order > 1) return true;

		// asymmetric
		return false;
	}

	public boolean isSignificant() throws StructureException {
		double symmetryThreshold = this.params.getSymmetryThreshold();
		return isSignificant(this.afpChain,this.ca1, symmetryThreshold);
	}

	/**
	 * If available, get the list of subalignments.<p>
	 * Should be length one unless {@link CESymmParameters#getMaxSymmOrder()}
	 * was set.
	 * @return List of AFP alignments
	 */
	public List<AFPChain> getAfpAlignments() {
		return afpAlignments;
	}

	@Override
	public MultipleAlignment align(List<Atom[]> atomArrays) 
			throws StructureException {

		if (params == null)	params = new CESymmParameters();
		return align(atomArrays, params);
	}

	@Override
	public MultipleAlignment align(List<Atom[]> atomArrays, Object param) 
			throws StructureException {

		if (atomArrays.size() != 1) {
			throw new IllegalArgumentException(
					"For symmetry analysis only one Structure is needed, "+
							atomArrays.size()+" Structures given.");
		}
		if (!(params instanceof CESymmParameters))
			throw new IllegalArgumentException("CE-Symm algorithm needs an "
					+ "object of call CESymmParameters as argument.");
		this.params = (CESymmParameters) param;

		//If the multiple axes is called, run iterative version
		if (params.isMultipleAxes() && 
				params.getRefineMethod() != RefineMethod.NOT_REFINED){
			CeSymmIterative iterative = new CeSymmIterative(params);
			MultipleAlignment result = iterative.execute(atomArrays.get(0));
			axes = iterative.getSymmetryAxes();
			return result;
		}

		//Otherwise perform only one CeSymm alignment
		AFPChain afp = align(atomArrays.get(0), atomArrays.get(0), params);

		if (refined){
			msa = SymmetryTools.fromAFP(afp, ca1);

			//STEP 5: symmetry alignment optimization
			if (this.params.getOptimization()){

				//Perform several optimizations in different threads
				try {
					ExecutorService executor = Executors.newCachedThreadPool();
					List<Future<MultipleAlignment>> future = 
							new ArrayList<Future<MultipleAlignment>>();
					int seed = this.params.getSeed();

					//Repeat the optimization in parallel
					for (int rep=0; rep<2; rep++){
						Callable<MultipleAlignment> worker = 
								new SymmOptimizer(msa, axes, seed+rep);
						Future<MultipleAlignment> submit = executor.submit(worker);
						future.add(submit);
					}

					//When finished take the one with the best MC-score
					double maxScore = Double.NEGATIVE_INFINITY;
					for (int rep=0; rep<future.size(); rep++){
						double score = future.get(rep).get().
								getScore(MultipleAlignmentScorer.MC_SCORE);
						if (score > maxScore){
							msa = future.get(rep).get();
							maxScore = score;
						}
					}
					executor.shutdown();

				} catch (InterruptedException e) {
					logger.warn("Optimization failed!");
				} catch (ExecutionException e) {
					logger.warn("Optimization failed!");
				}
			}
		} else {
			MultipleAlignmentEnsemble e = 
					new MultipleAlignmentEnsembleImpl(afp, ca1, ca1, false);
			msa = e.getMultipleAlignment(0);
		}

		return msa;
	}

	public SymmetryAxes getSymmetryAxes() {
		return axes;
	}
}
