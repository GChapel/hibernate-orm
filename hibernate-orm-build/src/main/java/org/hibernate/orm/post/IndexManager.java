/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.orm.post;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RelativePath;
import org.gradle.api.provider.Provider;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexWriter;
import org.jboss.jandex.Indexer;

/**
 * Encapsulates and manages a Jandex Index
 *
 * @author Steve Ebersole
 */
public class IndexManager {
	private final Configuration artifactsToProcess;
	private final Provider<RegularFile> indexFileReferenceAccess;
	private final Provider<RegularFile> packageFileReferenceAccess;
	private final Project project;

	private Index index;
	private TreeSet<Inclusion> internalPackageNames;

	public IndexManager(Configuration artifactsToProcess, Project project) {
		this.artifactsToProcess = artifactsToProcess;
		this.indexFileReferenceAccess = project.getLayout()
				.getBuildDirectory()
				.file( "reports/orm/indexing/jandex.idx" );
		this.packageFileReferenceAccess = project.getLayout()
				.getBuildDirectory()
				.file( "reports/orm/indexing/internal-packages.txt" );
		this.project = project;
	}

	public Configuration getArtifactsToProcess() {
		return artifactsToProcess;
	}

	public Provider<RegularFile> getIndexFileReferenceAccess() {
		return indexFileReferenceAccess;
	}

	public Provider<RegularFile> getPackageFileReferenceAccess() {
		return packageFileReferenceAccess;
	}

	public TreeSet<Inclusion> getInternalPackageNames() {
		return internalPackageNames;
	}

	public Index getIndex() {
		if ( index == null ) {
			throw new IllegalStateException( "Index has not been created yet" );
		}
		return index;
	}


	/**
	 * Used from {@link IndexerTask} as its action
	 */
	void index() {
		if ( index != null ) {
			assert internalPackageNames != null;
			return;
		}

		final Indexer indexer = new Indexer();
		internalPackageNames = new TreeSet<>( Comparator.comparing( Inclusion::getPath ) );

		// note: each of `artifacts` is a jar-file
		final Set<File> artifacts = artifactsToProcess.resolve();

		artifacts.forEach( (jar) -> {
			final FileTree jarFileTree = project.zipTree( jar );
			jarFileTree.visit(
					new FileVisitor() {
						private boolean isInOrmPackage(RelativePath relativePath) {
							return relativePath.getPathString().startsWith( "org/hibernate/" );
						}

						@Override
						public void visitDir(FileVisitDetails details) {
							final RelativePath relativePath = details.getRelativePath();
							if ( !isInOrmPackage( relativePath ) ) {
								return;
							}

							if ( relativePath.getPathString().endsWith( "internal" )
									|| relativePath.getPathString().endsWith( "internal/" ) ) {
								final String packageName = relativePath.toString().replace( '/', '.' );
								internalPackageNames.add( new Inclusion( packageName, true ) );
							}
						}

						@Override
						public void visitFile(FileVisitDetails details) {
							final RelativePath relativePath = details.getRelativePath();
							if ( !isInOrmPackage( relativePath ) ) {
								return;
							}

							if ( relativePath.getPathString().endsWith( ".class" ) ) {
								try ( final FileInputStream stream = new FileInputStream( details.getFile() ) ) {
									final ClassInfo indexedClassInfo = indexer.index( stream );
									if ( indexedClassInfo == null ) {
										project.getLogger()
												.lifecycle( "Problem indexing class file - " + details.getFile()
														.getAbsolutePath() );
									}
								}
								catch (FileNotFoundException e) {
									throw new RuntimeException( "Problem locating project class file - " + details.getFile()
											.getAbsolutePath(), e );
								}
								catch (IOException e) {
									throw new RuntimeException( "Error accessing project class file - " + details.getFile()
											.getAbsolutePath(), e );
								}
							}
						}
					}
			);
		} );

		this.index = indexer.complete();
		storeIndex();
		storePackageNames();
	}

	private void storeIndex() {
		final File indexFile = prepareOutputFile( indexFileReferenceAccess );

		try ( final FileOutputStream stream = new FileOutputStream( indexFile ) ) {
			final IndexWriter indexWriter = new IndexWriter( stream );
			indexWriter.write( index );
		}
		catch (FileNotFoundException e) {
			throw new RuntimeException( "Should never happen", e );
		}
		catch (IOException e) {
			throw new RuntimeException( "Error accessing index file - " + indexFile.getAbsolutePath(), e );
		}
	}

	private void storePackageNames() {
		final File packageNameFile = prepareOutputFile( packageFileReferenceAccess );

		try ( final FileWriter fileWriter = new FileWriter( packageNameFile ) ) {
			internalPackageNames.forEach( (inclusion) -> {
				try {
					fileWriter.write( inclusion.getPath() );
					fileWriter.write( '\n' );
				}
				catch (IOException e) {
					throw new RuntimeException( "Unable to write to package-name file - " + packageNameFile.getAbsolutePath(), e );
				}
			} );
		}
		catch (FileNotFoundException e) {
			throw new RuntimeException( "Should never happen", e );
		}
		catch (IOException e) {
			throw new RuntimeException( "Error accessing package-name file - " + packageNameFile.getAbsolutePath(), e );
		}
	}

	private File prepareOutputFile(Provider<RegularFile> outputFileReferenceAccess) {
		final File outputFile = outputFileReferenceAccess.get().getAsFile();
		if ( outputFile.exists() ) {
			outputFile.delete();
		}

		try {
			outputFile.getParentFile().mkdirs();
			outputFile.createNewFile();
		}
		catch (IOException e) {
			throw new RuntimeException( "Unable to create index file - " + outputFile.getAbsolutePath(), e );
		}

		return outputFile;
	}
}
