package dread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalysisPriority;
import ghidra.app.services.AnalyzerType;
import ghidra.framework.options.Options;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.FlowType;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;

public abstract class DreadAnalyzer extends AbstractAnalyzer {

	protected DreadAnalyzer(String name, String description, AnalyzerType type) {
		super(name, description, type);
	}
	
	@Override
	public boolean getDefaultEnablement(Program program) {
		return true;
	}
	
	interface GameHash {
		public default boolean programIsCompatible(Program program) {
			return program.getExecutableMD5().equals(compressed()) || program.getExecutableMD5().equals(decompressed());
		}
		public String version();
		public String compressed();
		public String decompressed();
	}
	
	protected String version;
	
	@Override
	public boolean canAnalyze(Program program) {
		
		final GameHash[] md5s = {
				new GameHash() {
					public String version() { return "1.0.0"; }
					public String compressed() { return "f5d9aa2af3abef3070791057060ee93c"; }
					public String decompressed() { return "0bfaa4258b49b560bb5bdf4d353ec0f6"; }
				},
				new GameHash() {
					// TODO: include md5s and remove compatibility override, for proper 1.0.1 support
					public boolean programIsCompatible(Program program) { return true; }
					public String version() { return "1.0.1"; }
					public String compressed() { return ""; }
					public String decompressed() { return ""; }
				}
			};
		if (!program.getExecutableFormat().equals("Nintendo Switch Binary")) { return false; }
		for (GameHash g : md5s) {
			if (g.programIsCompatible(program)) {
				version = g.version();
				return true;
			}
		}
		return false;
	}
	
	protected boolean forceRename = false;
	protected boolean forceReanalysis = false;
	@Override
	public void registerOptions(Options options, Program program) {
		options.registerOption("Force re-analysis", forceReanalysis, null,
				"Re-analyze even if a class has already been created");
		options.registerOption("Force renaming", forceRename, null,
				"Rename functions and classes, overwriting user-defined names");
	}
	@Override
	public void optionsChanged(Options options, Program program) {
		forceRename = options.getBoolean("Force renaming", forceRename);
		forceReanalysis = options.getBoolean("Force re-analysis", forceReanalysis);
	}
	
	protected SourceType sourceType() {
		return forceRename ? SourceType.USER_DEFINED : SourceType.ANALYSIS;
	}
	
	protected AnalysisPriority priority(int priority) {
		AnalysisPriority p = AnalysisPriority.FUNCTION_ID_ANALYSIS;
		for (int i = 0; i <= priority; i++) {
			p = p.getNext("DREAD"+i);
		}
		return p;
	}
	
	protected Namespace reflection(Program program) {
		try {
			return program.getSymbolTable().getOrCreateNameSpace(program.getGlobalNamespace(), "Reflection", SourceType.ANALYSIS);
		} catch (DuplicateNameException | InvalidInputException e) {
			return null;
		}
	}
	
	protected Function functionAt(Program program, String addr) {
		return functionAt(program, program.getAddressFactory().getAddress(addr));
	}
	
	protected Function functionAt(Program program, Address addr) {
		return program.getFunctionManager().getFunctionAt(addr);
	}
	
	protected HashMap<String, Function> getRequiredCallees(Program program) {
		HashMap<String, Function> required = new HashMap<String, Function>();
		if (version.equals("1.0.0")) {
			required.put("__cxa_guard_acquire", functionAt(program, "0x71011f3000"));
			required.put("__cxa_guard_release", functionAt(program, "0x71011f3010"));
		} else if (version.equals("1.0.1")) {
			required.put("__cxa_guard_acquire", functionAt(program, "0x71011f37e0"));
			required.put("__cxa_guard_release", functionAt(program, "0x71011f37f0"));
		}
		required.put("ReadConfigValue", functionAt(program, "0x71000003d4"));
		required.put("unk1", functionAt(program, "0x7100080124"));
		required.put("unk2", functionAt(program, "0x7100000250"));
		return required;
	}
	
	protected interface FuncWithParams {
		public Function function();
		public ArrayList<Reference> params();
	}
	
	protected ArrayList<FuncWithParams> callsWithParams(Program program, Function func) {
		ReferenceManager rm = program.getReferenceManager();
		
		ArrayList<Reference> allReferences = new ArrayList<Reference>();
		for (Address a : rm.getReferenceSourceIterator(func.getBody(), true)) {
			allReferences.addAll(Arrays.asList(rm.getReferencesFrom(a)));
		}
		
		ArrayList<FuncWithParams> funcsWithParams = new ArrayList<FuncWithParams>();
		ArrayList<Reference> params = new ArrayList<Reference>();
		for (Reference r : allReferences) {
			if (r.getReferenceType() == RefType.PARAM) {
				params.add(r);
			}
			else if ((r.getReferenceType() instanceof FlowType) && ((FlowType) r.getReferenceType()).isCall()) {
				final ArrayList<Reference> finalParams = new ArrayList<Reference>(params);
				funcsWithParams.add(new FuncWithParams() {
					private Function f = functionAt(program, r.getToAddress());
					private ArrayList<Reference> p = finalParams;
					public Function function() { return this.f; }
					public ArrayList<Reference> params() { return this.p; }
				});
				params = new ArrayList<Reference>();
			}
		}
		
		return funcsWithParams;
	}
}
