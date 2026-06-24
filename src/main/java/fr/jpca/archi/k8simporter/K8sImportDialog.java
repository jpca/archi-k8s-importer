/**
 * 
 */
package fr.jpca.archi.k8simporter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.archimatetool.editor.ui.IArchiImages;
import com.archimatetool.editor.ui.UIUtils;

import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;

/**
 * K8s Import Input Dialog
 * 
 */
public class K8sImportDialog extends TitleAreaDialog {


	public K8sImportDialog(Shell parentShell) {
		super(parentShell);
	}

	protected String[] namespaceList = new String[0];
	
    private Combo cbNamespaces;
    private Text txtDomain;
	//private Text txtClusterURL;
    //private Text txtToken;
    private Text txtConfigPath;
    
    private String namespace;
    private String domain;
    //private String clusterURL;
    //private String token;
    private String configPath;

	@Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Kubernetes Import");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        
        if((namespaceList != null) && (namespaceList.length > 0))
        	setMessage("Please select namespace to import from your local current Kubernetes context", IMessageProvider.INFORMATION);
        else
        	setMessage("No namespace retrieved from Kubernetes. Do you have a local kubectl able to connect to a cluster and the ACL to list namespaces?\nIf you only miss ACL, please type the namespace to import.", IMessageProvider.WARNING);
        
        setTitleImage(IArchiImages.ImageFactory.getImage(IArchiImages.ECLIPSE_IMAGE_IMPORT_PREF_WIZARD));
        setTitle("Kubernetes Import");

        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(1, false);
        container.setLayout(layout);

        Group convGroup = new Group(container, SWT.NULL);
        convGroup.setText("Conversion");
        convGroup.setLayout(new GridLayout(2, false));
        convGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        
    	cbNamespaces = createComboField(convGroup, "Kubernetes Namespace", SWT.FILL, namespaceList);
        txtDomain = createTextField(convGroup, "Application subfolder name", SWT.NONE);
        createFileControl(container);
        
        //txtClusterURL = createTextField(container, Messages.K8SImportDialog_ClusterURL, SWT.NONE);    
        //txtToken = createTextField(container, Messages.K8SImportDialog_ClusterToken, SWT.PASSWORD);
        
        return area;
    }
    
    protected Text createTextField(Composite container, String message, int style) {
        Label label = new Label(container, SWT.NONE);
        label.setText(message);
        
        Text txt = UIUtils.createSingleTextControl(container, SWT.BORDER | style, false);
        txt.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        
        return txt;
    }

    
    protected Combo createComboField(Composite container, String message, int style, String[] items ) {
        Label label = new Label(container, SWT.NONE);
        label.setText(message);
        
        Combo cb = new Combo(container, SWT.BORDER | style);
        if(items != null && items.length > 0)
        	cb.setItems(items);
        else
        {
        	// Texte de référence : 20 caractères "W"
        	// 'W' est l'un des caractères les plus larges → garantit la largeur minimale
        	String reference = "W".repeat(20);

        	// Mesure avec GC
        	GC gc = new GC(cb);
        	Point size = gc.textExtent(reference);
        	gc.dispose();

        	// Ajout d'un padding (optionnel)
        	int padding = 10;
        	int widthHint = size.x + padding;

        	// Application au layout
        	GridData gd = new GridData();
        	gd.widthHint = widthHint;
        	cb.setLayoutData(gd);

        	//cb.setSize(cb.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x + 150, SWT.DEFAULT);
        }
        
        return cb;
    }
    
    protected void createFileControl(Composite container) {
        
        Group confGroup = new Group(container, SWT.NULL);
        confGroup.setText("Configuration");
        confGroup.setLayout(new GridLayout(3, false));
        confGroup.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label label = new Label(confGroup, SWT.NULL);
        label.setText("File:");
        
        txtConfigPath = UIUtils.createSingleTextControl(confGroup, SWT.BORDER, false);
        txtConfigPath.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
              
        String defaultConfigPath = System.getProperty("user.home") + System.getProperty("file.separator") + IPreferenceConstants.K8SIMPORTER_CONFIG_DEFAULT_FILENAME;
        
        String lastConfigPath = K8sImporterPlugin.getInstance().getPreferenceStore().getString(IPreferenceConstants.K8SIMPORTER_PREFS_CONFIG_LOCATION);
        if(lastConfigPath == null || (lastConfigPath.trim().length() > 0) == false)
        	lastConfigPath = defaultConfigPath;
        
        File configFile = new File(lastConfigPath);
        
        if(configFile.exists()) {
            txtConfigPath.setText(configFile.getPath());
        }
        else {
            txtConfigPath.setText(defaultConfigPath);
        }
        
        Button fileButton = new Button(confGroup, SWT.PUSH);
        fileButton.setText("Choose...");
        fileButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                File file = chooseFile();
                if(file != null) {
                    txtConfigPath.setText(file.getPath());
                }
            }
        });
    }

    private File chooseFile() {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setText("Select Config file");
        
        File file = new File(txtConfigPath.getText());
        dialog.setFileName(file.getName());
        dialog.setFilterExtensions(IPreferenceConstants.K8SIMPORTER_PREFS_CONFIG_EXTENSION);
        
        String path = dialog.open();
        if(path == null) {
            return null;
        }
        
        return new File(path);
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    // save content of the Text fields because they get disposed
    // as soon as the Dialog closes
    protected void saveInput() {

    	namespace = cbNamespaces.getText().trim();
        domain = txtDomain.getText().trim();
        configPath = txtConfigPath.getText().trim();
        //token = txtToken.getText().trim();
        //clusterURL = txtClusterURL.getText().trim();
        storePreferences();
    }

    @Override
    protected void okPressed() {
        saveInput();
        super.okPressed();
    }
    
    @Override
    protected void cancelPressed() {
    	namespace = null;
    	super.cancelPressed();
    }
    
    public String getNamespace() {
        return namespace;
    }

    public String getDomain() {
        return domain;
    }
    
	public String getConfigPath() {
		return configPath;
	}
	
	void storePreferences() {
        IPreferenceStore store = K8sImporterPlugin.getInstance().getPreferenceStore();
        store.setValue(IPreferenceConstants.K8SIMPORTER_PREFS_LAST_DOMAIN, domain);
        store.setValue(IPreferenceConstants.K8SIMPORTER_PREFS_LAST_NAMESPACE, namespace);
        store.setValue(IPreferenceConstants.K8SIMPORTER_PREFS_CONFIG_LOCATION, getConfigPath());
    }

	public void setNamespaceList(V1NamespaceList nsl) {
		namespaceList = new String[0];
		List<String> al = new ArrayList<String>();
		for(V1Namespace ns : nsl.getItems())
		{
			al.add(ns.getMetadata().getName());
		}
		namespaceList = al.toArray(new String[0]);
		if(cbNamespaces != null)
			cbNamespaces.setItems(namespaceList);
	}	
}