package metaPb;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import main.Controller;
import main.Critere;
import main.Dmf;
import main.FeatureSelector;
import main.Learner;
import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.instance.RemoveRange;

public class ModelPerf extends AbstractPb implements Serializable {

	private static final long serialVersionUID = 6954058322424598690L;

	private Classifier classifier;
	private Filter fSelect;
	private File metaData;
	private File metaInstances;
	private File criteres;
	private File dmfs;
	private int idAlgo;
	private int idFsSearch;
	private int idFsEval;

	public ModelPerf(int jobNum, int idAlgo, int idFsSearch, int idFsEval, Learner learner, FeatureSelector featureSelector, File criteres, File dmfs,
			File metaData, File metaInstances) {
		// stocker le contexte
		super(jobNum);
		this.classifier = learner.getClassifier();
		this.fSelect = featureSelector.getFilter();
		this.metaData = metaData;
		this.metaInstances = metaInstances;
		this.criteres = criteres;
		this.dmfs = dmfs;
		this.idAlgo = idAlgo;
		this.idFsSearch = idFsSearch;
		this.idFsEval = idFsEval;
	}

	@SuppressWarnings("unchecked")
	public void work(File jobDir) {
		try {
			System.out.println("working on job " + jobNum + " in " + jobDir + " with parameters :");
			System.out.println("classifier=" + classifier);
			System.out.println("fSelect=" + fSelect);
			System.out.println("metaData=" + metaData);
			System.out.println("metaInstances=" + metaInstances);
			System.out.println("criteres=" + criteres);
			System.out.println("dmfs=" + dmfs);
			System.out.println("on : " + ManagementFactory.getRuntimeMXBean().getName());
			System.out.println("**************************");

			// Connexion à la base de données
			Connection connexion = Controller.connexion();
			Statement statement;
			ResultSet rset;

			// deserialiser criteres et dmfs
			ObjectInputStream ois = new ObjectInputStream(new FileInputStream(criteres));
			ArrayList<Critere> critList = (ArrayList<Critere>) ois.readObject();
			ois.close();
			ois = new ObjectInputStream(new FileInputStream(dmfs));
			ArrayList<Dmf> dmfList = (ArrayList<Dmf>) ois.readObject();
			ois.close();

			// ouvrir les workbooks
			FileInputStream streamMD = new FileInputStream(metaData);
			OPCPackage opcMD = OPCPackage.open(streamMD);
			Workbook workbookMD = WorkbookFactory.create(opcMD);

			FileInputStream streamMI = new FileInputStream(metaInstances);
			OPCPackage opcMI = OPCPackage.open(streamMI);
			Workbook workbookMI = WorkbookFactory.create(opcMI);
			Sheet sheetMI = workbookMI.getSheetAt(0);

			// lister les algos de base par id
			Sheet sheetMD = workbookMD.getSheetAt(0);
			ArrayList<String> algosBase = new ArrayList<String>();
			for (Cell cell : sheetMD.getRow(0)) {
				if (cell.getColumnIndex() != 0) {
					algosBase.add("" + (int) cell.getNumericCellValue());
				}
			}

			// forall criteres
			for (Critere crit : critList) {
				try {
					// check les criteres a run
					int nbRuns = 0;
					statement = connexion.createStatement();
					rset = statement.executeQuery("SELECT count(distinct dataset) as nb FROM `runs` WHERE metaproblem=\"" + this.getClass().getSimpleName()
							+ "\" AND algorithm=\"" + this.idAlgo + "\" AND fsearch=\"" + this.idFsSearch + "\" AND feval=\"" + this.idFsEval
							+ "\" AND criterion=\"" + crit.getNom() + "\"");
					while (rset.next()) {
						nbRuns = rset.getInt("nb");
					}
					rset.close();
					statement.close();
					if (nbRuns > Controller.minRunsPerCrit) {
						System.err.println("Critere deja test : " + crit.getNom());
						throw new Exception("Critere deja test");
					}

					// ************************
					// construire metadataset de base
					// ************************
					sheetMI = workbookMI.getSheetAt(0);

					// ouvrir MD a la bonne page
					sheetMD = null;
					for (Sheet sheet : workbookMD) {
						Cell c = sheet.getRow(0).getCell(0);
						if (c.getStringCellValue().equals(crit.getNom())) {
							sheetMD = sheet;
						}
					}
					if (sheetMD == null)
						throw new Exception("Critere manquant/erroné " + crit.getNom() + " dans " + metaData.toString());

					// construire vect dmf et label
					ArrayList<Attribute> metaAttributes = new ArrayList<Attribute>();
					// id = int
					metaAttributes.add(new Attribute("datasetId"));
					for (Dmf dmf : dmfList) {
						// tous les dmf sont numeriques donc constructeur unique
						metaAttributes.add(new Attribute(dmf.getNom()));
					}
					// labels = perf algos donc numerique
					metaAttributes.add(new Attribute("label"));

					// le meta dataset, avec le label de classe en dernier attribut
					Instances metaDataset = new Instances("ModelPerf_" + crit.getNom(), metaAttributes, sheetMI.getLastRowNum());
					metaDataset.setClassIndex(metaDataset.numAttributes() - 1);

					Instance instance = null;
					double[] values = null;
					for (Row instanceRow : sheetMI) {
						// chaque ligne est une nouvelle meta instance
						if (instanceRow.getRowNum() != 0 && instanceRow.getCell(0) != null && instanceRow.getCell(0).getCellType() == Cell.CELL_TYPE_NUMERIC) {

							values = new double[metaAttributes.size()];
							// remplir id
							values[0] = instanceRow.getCell(0).getNumericCellValue();
							// remplir les dmf
							for (int col = 1; col < metaAttributes.size() - 1; col++) {
								// verifier que l'on renseigne le bon meta attribut
								if (sheetMI.getRow(0).getCell(col).getStringCellValue().equals(((Attribute) metaAttributes.get(col)).name())) {
									if (instanceRow.getCell(col).getCellType() == Cell.CELL_TYPE_NUMERIC) {
										// valeur présente
										values[col] = instanceRow.getCell(col).getNumericCellValue();
									} else {
										// valeur manquante
										values[col] = Utils.missingValue();
									}
								} else {
									throw new Exception("Criteres incompatibles ou mal ordonnés : " + sheetMI.getRow(0).getCell(col).getStringCellValue()
											+ " et " + ((Attribute) metaAttributes.get(col)).name());
								}
							}

							// on laisse la classe vide pour le moment
							values[metaAttributes.size() - 1] = 0;
							instance = new DenseInstance(1.0, values);
							metaDataset.add(instance);
							values = null;

						}
					}
					// ordonner le meta datasset
					metaDataset.compactify();

					// System.out.println("");
					// System.out.println("********* Dataset **********");
					// System.out.println(metaDataset.toString());
					// System.out.println("");

					// ************************
					// forall algo de base : un meta dataset -> une prediction par instance
					// ************************
					// structure de donnee pour les predictions
					String[] algoPred = new String[metaDataset.numInstances()];
					double[] valPred = new double[metaDataset.numInstances()];
					if (crit.isHigherIsBetter()) {
						valPred[0] = Double.MIN_VALUE;
					} else {
						valPred[0] = Double.MAX_VALUE;
					}
					for (int i = 1; i < valPred.length; i++) {
						valPred[i] = valPred[i - 1];
					}

					for (int i = 0; i < algosBase.size(); i++) {

						// copier le metadataset
						Instances metaDatasetCurr = new Instances(metaDataset);

						// calculer les labels
						for (int j = 0; j < metaDatasetCurr.numInstances(); j++) {
							Instance currInst = metaDatasetCurr.instance(j);
							// ici on veut le score de l'algo courant sur l'instance considérée
							for (Row datasetRow : sheetMD) {
								// on se place sur la ligne de l'instance courante

								if (datasetRow.getRowNum() != 0 && datasetRow.getCell(0) != null
										&& datasetRow.getCell(0).getNumericCellValue() == currInst.value(0)) {
									// sur la colonne de l'algo courant
									for (Cell cell : sheetMD.getRow(0)) {
										if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC && ("" + (int) cell.getNumericCellValue()).equals(algosBase.get(i))) {
											// label = valeur
											if (datasetRow.getCell(cell.getColumnIndex()) != null
													&& datasetRow.getCell(cell.getColumnIndex()).getCellType() == Cell.CELL_TYPE_NUMERIC
													&& crit.test(datasetRow.getCell(cell.getColumnIndex()).getNumericCellValue())) {
												currInst.setClassValue(datasetRow.getCell(cell.getColumnIndex()).getNumericCellValue());
											} else {
												currInst.setClassMissing();
											}
										}
									}
								}
							}
						}

						// ************************
						// forall meta instances (=dataset) leave one out
						// ************************
						for (int j = 0; j < metaDatasetCurr.numInstances(); j++) {
							try {

								// retirer l'instance courante
								String[] optTrain = new String[2];
								optTrain[0] = "-R";
								optTrain[1] = (j + 1) + "";
								RemoveRange trainRemover = new RemoveRange();
								trainRemover.setOptions(optTrain);
								trainRemover.setInputFormat(metaDatasetCurr);
								Instances trainSet = Filter.useFilter(metaDatasetCurr, trainRemover);

								// retirer tout sauf l'instance courante
								String[] optTest = new String[3];
								optTest[0] = "-R";
								optTest[1] = (j + 1) + "";
								optTest[2] = "-V";
								RemoveRange testRemover = new RemoveRange();
								testRemover.setOptions(optTest);
								testRemover.setInputFormat(metaDatasetCurr);
								Instances testSet = Filter.useFilter(metaDatasetCurr, testRemover);

								// retirer les id
								String[] optRem = new String[2];
								optRem[0] = "-R";
								optRem[1] = "1";
								Remove idRemover = new Remove();
								idRemover.setOptions(optRem);
								idRemover.setInputFormat(trainSet);
								trainSet = Filter.useFilter(trainSet, idRemover);
								testSet = Filter.useFilter(testSet, idRemover);

								// ************************
								// meta feature selection
								// ************************

								// batch filtering = transformation uniforme du training et testing set
								fSelect.setInputFormat(trainSet);
								trainSet = Filter.useFilter(trainSet, fSelect);
								testSet = Filter.useFilter(testSet, fSelect);

								// ************************
								// modelisation et prediction
								// ************************

								// prediction pour perf du run
								classifier.buildClassifier(trainSet);
								double pred = classifier.classifyInstance(testSet.firstInstance());

								// si prediction plus optimiste, maj de la prediction finale
								if ((crit.isHigherIsBetter() && pred > valPred[j]) || (!crit.isHigherIsBetter() && pred < valPred[j])) {
									valPred[j] = pred;
									algoPred[j] = algosBase.get(i);
								}

							} catch (Exception e) {
								System.err.println("Fail de modelisation/prediction sur meta instance, datsetID = " + metaDatasetCurr.instance(j).value(0)
										+ " pour " + algosBase.get(i));
								//e.printStackTrace();
							}
						} // end forall instances

					} // end forall algos

					// reboucle sur les meta instances (=dataset) pour calcul de perf
					for (int j = 0; j < metaDataset.numInstances(); j++) {
						try {

							Instance currInst = metaDataset.instance(j);

							// meilleur des algos en perf prédite : algoPred[j]

							double valCurr = Double.MIN_VALUE;
							double valDef = Double.MIN_VALUE;
							double valBest = 0;
							// on cherche le meilleur score (max ou min)
							if (crit.isHigherIsBetter()) {
								valBest = Double.MIN_VALUE;
							} else {
								valBest = Double.MAX_VALUE;
							}
							for (Row datasetRow : sheetMD) {
								// on se place sur la ligne de l'instance courante
								if (datasetRow.getRowNum() != 0 && datasetRow.getCell(0).getNumericCellValue() == currInst.value(0)) {
									for (Cell cell : sheetMD.getRow(0)) {
										int col = cell.getColumnIndex();
										if (col > 0 && datasetRow.getCell(col) != null && datasetRow.getCell(col).getCellType() == Cell.CELL_TYPE_NUMERIC) {
											// on cherche la perf reelle de l'algo predit
											if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC && ("" + (int) cell.getNumericCellValue()).equals(algoPred[j])) {
												valCurr = datasetRow.getCell(col).getNumericCellValue();
											}
											// la perf de ZeroR (defaut)
											if (!crit.isUseDef() && cell.getCellType() == Cell.CELL_TYPE_NUMERIC
													&& ((int) cell.getNumericCellValue() == 1069)) {
												valDef = datasetRow.getCell(col).getNumericCellValue();
											}
											// et la meilleure perf sur ce dataset
											if (((crit.isHigherIsBetter() && datasetRow.getCell(col).getNumericCellValue() > valBest)
													|| (!crit.isHigherIsBetter() && datasetRow.getCell(col).getNumericCellValue() < valBest))
													&& crit.test(datasetRow.getCell(col).getNumericCellValue())) {
												valBest = datasetRow.getCell(col).getNumericCellValue();
											}
										}
									}
								}
							}
							// test de mauvais valCurr
							if (valCurr == Double.MIN_VALUE || !crit.test(valCurr)) {
								throw new Exception("Fail : valeur manquante/erronee du critere " + crit.getNom() + " pour l'algo predit " + algoPred[j]
										+ " sur le dataset " + currInst.value(0) + " avec " + valCurr);
							}
							// test de mauvais valDef
							if (valDef == Double.MIN_VALUE || !crit.test(valDef)) {
								// toujours valeur de critere par defaut ?
								if (crit.isUseDef()) {
									valDef = crit.getDef();
								} else {
									System.err.println("Fail : valeur manquante/erronee du critere " + crit.getNom()
											+ " pour l'algo ZeroR (1069) sur le dataset " + currInst.value(0) + " avec " + valDef);
									valDef = Double.MIN_VALUE;
								}
							}
							// si best = defaut résultat sans intéret
							if (valDef == valBest) {
								throw new Exception(
										"Fail : ZeroR meilleur score de " + crit.getNom() + " sur le dataset " + currInst.value(0) + " avec " + valDef);
							}
							// et on calcule la performance du run
							double perf = 0;
							if (valDef != Double.MIN_VALUE)
								perf = 1 - ((valBest - valCurr) / (valBest - valDef));

							// ************************
							// ajouter resultats
							// ************************
							statement = connexion.createStatement();
							statement.executeUpdate(
									"INSERT INTO `runs` (`metaproblem`, `algorithm`, `fsearch`, `feval`, `criterion`, `dataset`, `value`, `performance`) VALUES ('"
											+ this.getClass().getSimpleName() + "','" + this.idAlgo + "','" + this.idFsSearch + "','" + this.idFsEval + "','"
											+ crit.getNom() + "','" + (int) currInst.value(0) + "','" + valCurr + "','" + perf + "')");
							statement.close();

						} catch (Exception e) {
							//System.err.println("Fail du calcul de perf sur la meta instance, datsetID = " + metaDataset.instance(j).value(0));
							// e.printStackTrace();
						}
					} // end forall instances

				} catch (Exception e) {
					System.err.println("Fail sur le critere : " + crit.getNom());
					// e.printStackTrace();
				}
			} // end forall criteres

			// fermer les workbooks
			opcMD.close();
			streamMD.close();
			opcMI.close();
			streamMI.close();

			// fermer la connexion
			connexion.close();

		} catch (Exception e) {
			System.err.println("Fail on init or exit");
			e.printStackTrace();
		}
	}
}
