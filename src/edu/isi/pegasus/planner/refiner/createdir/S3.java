/**
 *  Copyright 2007-2008 University Of Southern California
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


package edu.isi.pegasus.planner.refiner.createdir;

import edu.isi.pegasus.planner.catalog.site.classes.GridGateway;
import edu.isi.pegasus.planner.catalog.site.classes.SiteCatalogEntry;
import edu.isi.pegasus.planner.catalog.site.classes.SiteStore;


import edu.isi.pegasus.planner.classes.Job;
import edu.isi.pegasus.planner.classes.PegasusBag;

import edu.isi.pegasus.common.logging.LogManager;
import edu.isi.pegasus.planner.common.PegasusProperties;

import edu.isi.pegasus.planner.namespace.ENV;
import edu.isi.pegasus.planner.namespace.Pegasus;

import edu.isi.pegasus.planner.catalog.TransformationCatalog;
import edu.isi.pegasus.planner.catalog.transformation.TransformationCatalogEntry;

import edu.isi.pegasus.planner.catalog.transformation.classes.TCType;

import edu.isi.pegasus.common.util.S3cfg;
import edu.isi.pegasus.common.util.Separator;

import edu.isi.pegasus.planner.catalog.site.classes.FileServer;
import java.io.File;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.isi.pegasus.planner.code.GridStartFactory;


/**
 * The default implementation for creating create dir jobs.
 * 
 * @author  Karan Vahi
 * @version $Revision$
 */
public class S3 implements Implementation {

    /**
     * The transformation namespace for the amazon bucket creation jobs.
     */
    public static final String TRANSFORMATION_NAMESPACE = "pegasus";

    /**
     * The logical name of the transformation that creates buckets on the
     * amazon S3Strategy.
     */
    public static final String TRANSFORMATION_NAME = "s3";

    /**
     * The version number for the derivations for create dir  jobs.
     */
    public static final String TRANSFORMATION_VERSION = null;

    /**
     * The basename of the pegasus cleanup executable.
     */
    public static final String EXECUTABLE_BASENAME = "pegasus-s3";

    /**
     * The complete TC name for the amazon s3cmd.
     */
    public static final String COMPLETE_TRANSFORMATION_NAME = Separator.combine(
                                                                 TRANSFORMATION_NAMESPACE,
                                                                 TRANSFORMATION_NAME,
                                                                 TRANSFORMATION_VERSION  );

    /**
     * The derivation namespace for the amazon bucket creation jobs.
     */
    public static final String DERIVATION_NAMESPACE = "pegasus";

    /**
     * The logical name of the transformation that creates buckets on the
     * amazon S3Strategy.
     */
    public static final String DERIVATION_NAME = "pegasus-s3";


    /**
     * The version number for the derivations for create dir  jobs.
     */
    public static final String DERIVATION_VERSION = "1.0";
    
    /**
     * The path to be set for create dir jobs.
     */
    public static final String PATH_VALUE = ".:/bin:/usr/bin:/usr/ucb/bin";

    /**
     * The handle to the transformation catalog.
     */
    protected TransformationCatalog mTCHandle;
    
    /**
     * The handle to the SiteStore.
     */
    protected SiteStore mSiteStore;
    
    /**
     * The handle to the logging object.
     */
    protected LogManager mLogger;
    
    /**
     * The handle to the pegasus properties.
     */
    protected PegasusProperties mProps;
    
    /**
     * The path to the s3cfg file on the submit host (local pool).
     */
    protected String mLocalS3cfg;

    /**
     * The basename of the user s3cfg file
     */
    protected String mLocalS3cfgBasename;
    
    
    /**
     * The name of the bucket that is created.
     */
    //protected String mBucketName;
    
    
    /**
     * The directory to be created in the already existing bucket.
     */
    protected String mRelativeBucketDir;
    
    /**
     * Map mapping a site to the bucket to be used.
     */
    protected Map<String,String> mBucketMap;
    
    
    /**
     * Intializes the class.
     *
     * @param bag      bag of initialization objects
     */
    public void initialize( PegasusBag bag ) {
        mLogger     = bag.getLogger();
        mSiteStore  = bag.getHandleToSiteStore();
        mTCHandle   = bag.getHandleToTransformationCatalog();
        mProps      = bag.getPegasusProperties();
        
        mBucketMap  = new HashMap<String,String>();
        
        mRelativeBucketDir = bag.getPlannerOptions().getRelativeDirectory();

        //replace file separators in directory with -
        mRelativeBucketDir = mRelativeBucketDir.replace( File.separatorChar,  '-' );

        
        mLocalS3cfg = S3cfg.getPathToS3cfg(bag);
        //set the path to s3cfg only if the scfg exists
        if( mLocalS3cfg != null && !new File(mLocalS3cfg).exists() ){
            mLogger.log( "The s3cfg file does not exist - " + mLocalS3cfg,
                         LogManager.DEBUG_MESSAGE_LEVEL );
            mLocalS3cfg = null;
        }

        mLocalS3cfgBasename = (mLocalS3cfg == null) ?
                                  null :
                                  new File(mLocalS3cfg).getName();
        
    }
    
    
    
    /**
     * Return the s3 accessible URL for the bucket created by this implementation.
     * 
     * @param site   the site in the site catalog for which the bucket name is
     *               required.
     * 
     * @return the name of the S3 bucket for the site.
     */
    public String getBucketNameURL( String site ){
        if( mBucketMap.containsKey( site ) ){
            return mBucketMap.get( site );
        }
        else{
            SiteCatalogEntry entry = mSiteStore.lookup( site );
            if( entry == null ){
                throw new RuntimeException( "No entry in site catalog for site " + site );
            }
            //retrieve the base mount point that is the bucket name
            StringBuffer url = new StringBuffer();
            FileServer fs = entry.getHeadNodeFS().selectScratchSharedFileServer();
            url.append( fs.getURLPrefix() );
            
            //add internal mount point
            String bucketName  = entry.getInternalMountPointOfWorkDirectory();
            if ( bucketName == null ||  bucketName.trim().length() == 0 ||
                 bucketName.equals( "/" )  || bucketName.equals( "/tmp" ) ){
                //do nothing just add relative bucket
                //Site Catalog XML2 Parser adds /tmp for workdirectory if none
                //is specified
            }
            else{
                //use existing bucket first
                url.append( bucketName );
            }
            
            url.append( File.separator );
            url.append( this.mRelativeBucketDir );
            mLogger.log( "For site " + site + " bucket used is " + url.toString() ,
                        LogManager.DEBUG_MESSAGE_LEVEL );
            mBucketMap.put( site, url.toString() );
            return url.toString();
        }
    }
    
    /**
     * It creates a noop job that executes on the submit host. The directory in 
     * the bucket is created when s3cmd issues get or put requests
     * to the bucket.
     * 
     * @param site  the execution site for which the create dir job is to be
     *                  created.
     * @param name  the name that is to be assigned to the job.     * 
     * @param directory  the directory to be created on the site.
     *
     * @return create dir job.
     */
    public Job makeCreateDirJob( String site, String name, String directory ) {
        //figure out if we need to create a bucket or not.
        SiteCatalogEntry entry = mSiteStore.lookup( site );
        if( entry == null ){
            throw new RuntimeException( "No entry in site catalog for site " + site );
        }
        
        Job createDirJob = null;
        String bucketName  = entry.getInternalMountPointOfWorkDirectory();
        
        if ( bucketName == null ||  bucketName.trim().length() == 0 ||
             bucketName.equals( "/" )  || bucketName.equals( "/tmp" ) ){
            //Site Catalog XML2 Parser adds /tmp for workdirectory if none
            //is specified
            mLogger.log( "Creating a create bucket job for site "  + site,
                         LogManager.DEBUG_MESSAGE_LEVEL );
            createDirJob = this.makeCreateBucketJob(site, name, directory);
        }
        else{
            //we create a NOOP job
            mLogger.log( "Creating a NOOP create dir job for site " + site + 
                         " as we will use existing bucket " + bucketName,
                         LogManager.DEBUG_MESSAGE_LEVEL );
            createDirJob = createNoOPJob( name );
        }
       return createDirJob;
    }


    /**
     * It creates a NoOP job that runs on the submit host.
     *
     * @param name the name to be assigned to the noop job
     *
     * @return  the noop job.
     */
    protected Job createNoOPJob( String name ) {

        Job newJob = new Job();
        List entries = null;
        String execPath =  null;

        //jobname has the dagname and index to indicate different
        //jobs for deferred planning
        newJob.setName( name );
        newJob.setTransformation( "pegasus", "noop", "1.0" );
        newJob.setDerivation( "pegasus", "noop", "1.0" );

//        newJob.setUniverse( "vanilla" );
        newJob.setUniverse( GridGateway.JOB_TYPE.auxillary.toString());
                
        //the noop job does not get run by condor
        //even if it does, giving it the maximum
        //possible chance
        newJob.executable = "/bin/true";

        //construct noop keys
        newJob.setSiteHandle( "local" );
        newJob.setJobType( Job.CREATE_DIR_JOB );
        construct(newJob,"noop_job","true");
        construct(newJob,"noop_job_exit_code","0");

        //we do not want the job to be launched
        //by kickstart, as the job is not run actually
        newJob.vdsNS.checkKeyInNS( Pegasus.GRIDSTART_KEY,
                                   GridStartFactory.GRIDSTART_SHORT_NAMES[GridStartFactory.NO_GRIDSTART_INDEX] );

        return newJob;

    }

    /**
     * Constructs a condor variable in the condor profile namespace
     * associated with the job. Overrides any preexisting key values.
     *
     * @param job   contains the job description.
     * @param key   the key of the profile.
     * @param value the associated value.
     */
    protected void construct(Job job, String key, String value){
        job.condorVariables.checkKeyInNS(key,value);
    }

    
    
    
    /**
     * It creates a new bucket in S3 using the s3cmd tool.
     *
     * @param site  the execution site for which the create dir job is to be
     *                  created.
     * @param name  the name that is to be assigned to the job.     * 
     * @param directory  the directory to be created on the site.
     *
     * @return create dir job.
     */
    public Job makeCreateBucketJob( String site, String name, String directory ) {
        Job newJob  = new Job();
        List entries    = null;
        String execPath = null;
        TransformationCatalogEntry entry   = null;
        
//        GridGateway jobManager = null;

        try {
            entries = mTCHandle.lookup( S3.TRANSFORMATION_NAMESPACE,
                                              S3.TRANSFORMATION_NAME,
                                              S3.TRANSFORMATION_VERSION,
                                              "local", 
                                              TCType.INSTALLED);
        }
        catch (Exception e) {
            //non sensical catching
            mLogger.log("Unable to retrieve entries from TC " +
                        e.getMessage(), LogManager.DEBUG_MESSAGE_LEVEL );
        }

        entry = ( entries == null ) ?
            this.defaultTCEntry( "local" ): //try using a default one
            (TransformationCatalogEntry) entries.get(0);

        if( entry == null ){
            //NOW THROWN AN EXCEPTION

            //should throw a TC specific exception
            StringBuffer error = new StringBuffer();
            error.append("Could not find entry in tc for lfn ").
                append( COMPLETE_TRANSFORMATION_NAME ).
                append(" at site ").append( "local" );

            mLogger.log( error.toString(), LogManager.ERROR_MESSAGE_LEVEL);
            throw new RuntimeException( error.toString() );
        }


        // map to local site
        SiteCatalogEntry ePool = mSiteStore.lookup( "local" );
/*      JIRA PM-277 
        jobManager = ePool.selectGridGateway( GridGateway.JOB_TYPE.cleanup );
*/
        String argString = null;
        
        
        execPath = entry.getPhysicalTransformation();

        //argString = " mb " + "s3://" +   mBucketName;
        argString = " mkdir " + this.getBucketNameURL( site );
              
        newJob.jobName = name;
        newJob.setTransformation( S3.TRANSFORMATION_NAMESPACE,
                                  S3.TRANSFORMATION_NAME,
                                  S3.TRANSFORMATION_VERSION  );
        newJob.setDerivation( S3.DERIVATION_NAMESPACE,
                              S3.DERIVATION_NAME,
                              S3.DERIVATION_VERSION  );
 /*     JIRA PM-277  
        newJob.condorUniverse = jobManager.getJobType().toString();
        newJob.globusScheduler = jobManager.getContact();
 */ 
        newJob.executable = execPath;
        newJob.executionPool = "local";
        newJob.strargs = argString;
        newJob.jobClass = Job.CREATE_DIR_JOB;
        newJob.jobID = name;

        //the profile information from the pool catalog needs to be
        //assimilated into the job.
        newJob.updateProfiles( ePool.getProfiles() );

        //add any notifications specified in the transformation
        //catalog for the job. JIRA PM-391
        newJob.addNotifications( entry );


        //the profile information from the transformation
        //catalog needs to be assimilated into the job
        //overriding the one from pool catalog.
        newJob.updateProfiles(entry);

        //the profile information from the properties file
        //is assimilated overidding the one from transformation
        //catalog.
        newJob.updateProfiles( mProps );
        
        // s3cfg - for jobs executing on local site, just set the environment variable
        // for remote execution, transfer the s3cfg file
        if( newJob.getSiteHandle().equalsIgnoreCase( "local" ) ){
            //the full path
            newJob.envVariables.checkKeyInNS(S3cfg.S3CFG, this.mLocalS3cfg );
        }
        else{
            newJob.condorVariables.addIPFileForTransfer(mLocalS3cfg);
            //just the basename
            newJob.envVariables.checkKeyInNS(ENV.S3CFG, mLocalS3cfgBasename);
            newJob.envVariables.checkKeyInNS(ENV.GRIDSTART_PREJOB,
                                             "/bin/chmod 600 " +
                                             mLocalS3cfgBasename);
        }
        
        return newJob;

    }


    /**
     * Returns a default TC entry to be used in case entry is not found in the
     * transformation catalog.
     *
     * @param site   the site for which the default entry is required.
     *
     *
     * @return  the default entry.
     */
    private  TransformationCatalogEntry defaultTCEntry( String site ){
        TransformationCatalogEntry defaultTCEntry = null;
        //check if PEGASUS_HOME is set
        String home = mSiteStore.getPegasusHome( site );
        //if PEGASUS_HOME is not set, use VDS_HOME
        home = ( home == null )? mSiteStore.getVDSHome( site ): home;

        mLogger.log( "Creating a default TC entry for " +
                     COMPLETE_TRANSFORMATION_NAME +
                     " at site " + site,
                     LogManager.DEBUG_MESSAGE_LEVEL );

        //if home is still null
        if ( home == null ){
            //cannot create default TC
            mLogger.log( "Unable to create a default entry for " +
                         COMPLETE_TRANSFORMATION_NAME,
                         LogManager.DEBUG_MESSAGE_LEVEL );
            //set the flag back to true
            return defaultTCEntry;
        }

        //remove trailing / if specified
        home = ( home.charAt( home.length() - 1 ) == File.separatorChar )?
            home.substring( 0, home.length() - 1 ):
            home;

        //construct the path to it
        StringBuffer path = new StringBuffer();
        path.append( home ).append( File.separator ).
            append( "bin" ).append( File.separator ).
            append( S3.EXECUTABLE_BASENAME );


        defaultTCEntry = new TransformationCatalogEntry( S3.TRANSFORMATION_NAMESPACE,
                                                         S3.TRANSFORMATION_NAME,
                                                         S3.TRANSFORMATION_VERSION );

        defaultTCEntry.setPhysicalTransformation( path.toString() );
        defaultTCEntry.setResourceId( site );
        defaultTCEntry.setType( TCType.INSTALLED );
        defaultTCEntry.setSysInfo( this.mSiteStore.lookup( site ).getSysInfo() );

        //register back into the transformation catalog
        //so that we do not need to worry about creating it again
        try{
            mTCHandle.insert( defaultTCEntry , false );
        }
        catch( Exception e ){
            //just log as debug. as this is more of a performance improvement
            //than anything else
            mLogger.log( "Unable to register in the TC the default entry " +
                          defaultTCEntry.getLogicalTransformation() +
                          " for site " + site, e,
                          LogManager.DEBUG_MESSAGE_LEVEL );
        }

        return defaultTCEntry;

    }

}
