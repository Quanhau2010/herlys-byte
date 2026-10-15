package dev.herlysbyte.ui;

import dev.herlysbyte.analysis.ClassInfo;
import dev.herlysbyte.analysis.BytecodeDisassembler;
import dev.herlysbyte.analysis.DecompilerService;
import dev.herlysbyte.analysis.JarAnalysis;
import dev.herlysbyte.analysis.JarAnalyzer;
import dev.herlysbyte.analysis.JarResourceInfo;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Screen;
import javafx.geometry.Rectangle2D;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.Future;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HerlysByteApp extends Application {
    private final JarAnalyzer analyzer = new JarAnalyzer();
    private final DecompilerService decompiler = new DecompilerService();
    private final BytecodeDisassembler bytecodeDisassembler = new BytecodeDisassembler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "jar-analysis"); t.setDaemon(true); return t; });
    private final ExecutorService classWorker = Executors.newFixedThreadPool(2,r -> { Thread t = new Thread(r, "class-inspection"); t.setDaemon(true); return t; });
    private final TreeView<BrowserNode> classTree = new TreeView<>();
    private final TextField search = new TextField();
    private final Label status = new Label("Mở hoặc kéo thả một tệp .jar để bắt đầu");
    private final Label title = new Label("Chưa mở JAR"), stats = new Label("—");
    private final TextArea details = new TextArea();
    private final TextArea classDetails = new TextArea();
    private final TextArea sourceView = new TextArea();
    private final TextArea stringsView = new TextArea();
    private final TextArea dependenciesView = new TextArea();
    private final TextArea bytecodeView = new TextArea();
    private final TableView<JarResourceInfo> resourceTable = new TableView<>();
    private final BorderPane resourcesPane = new BorderPane();
    private final TabPane classTabs = new TabPane();
    private final ProgressIndicator activity = new ProgressIndicator();
    private Button exportSourceButton, copySourceButton;
    private Stage primaryStage;
    private Future<?> activeClassTask;
    private Future<?> activeAnalysisTask;
    private final java.util.concurrent.atomic.AtomicLong analysisGeneration=new java.util.concurrent.atomic.AtomicLong();
    private String currentSource = "";
    private double dragOffsetX,dragOffsetY,resizeStartX,resizeStartY,resizeStartWidth,resizeStartHeight;
    private int resizeEdges;
    private static final int EDGE_LEFT=1,EDGE_RIGHT=2,EDGE_TOP=4,EDGE_BOTTOM=8;
    private final HBox classView = new HBox();
    private final StackPane workArea = new StackPane();
    private final java.util.Map<String,Button> navButtons = new java.util.LinkedHashMap<>();
    private JarAnalysis current;
    private String activeTab = "Tổng quan";
    private static final String BG = "#111318", PANEL = "#1a1d24", BORDER = "#2a2e38", TEXT = "#e6e8ed", MUTED = "#9aa1ac", PURPLE = "#b89cff";
    private record BrowserNode(String name, ClassInfo classInfo) { boolean isPackage(){ return classInfo == null; } }

    @Override public void start(Stage stage) {
        primaryStage=stage;
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setResizable(true);
        stage.setTitle("HerlysByte — Java Bytecode Analyzer");
        BorderPane appRoot = new BorderPane(); appRoot.setStyle("-fx-background-color:" + BG + ";-fx-font-family:'Inter','Arial';");
        appRoot.setTop(topbar(stage)); appRoot.setLeft(sidebar()); appRoot.setCenter(content()); appRoot.setBottom(statusbar());
        BorderPane shell=new BorderPane();shell.setStyle("-fx-background-color:"+BG+";-fx-border-color:#383b45;-fx-border-width:1;");shell.setTop(windowTitlebar(stage));shell.setCenter(appRoot);
        Rectangle2D bounds=Screen.getPrimary().getVisualBounds();double initialW=Math.min(1400,bounds.getWidth()-32),initialH=Math.min(860,bounds.getHeight()-48);
        Scene scene = new Scene(shell, initialW, initialH); scene.getStylesheets().add(getClass().getResource("/herlysbyte.css").toExternalForm()); stage.setMinWidth(800); stage.setMinHeight(540); stage.setScene(scene); updateNavigation(); stage.show();
        stage.setX(bounds.getMinX()+(bounds.getWidth()-initialW)/2);stage.setY(bounds.getMinY()+(bounds.getHeight()-initialH)/2);
        scene.addEventFilter(KeyEvent.KEY_PRESSED,this::handleShortcut);
        installWindowResize(scene,stage);
        shell.setOnDragOver(e -> { if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY); e.consume(); });
        shell.setOnDragDropped(e -> { Dragboard db=e.getDragboard(); boolean ok=false; if(db.hasFiles()){ open(db.getFiles().get(0).toPath()); ok=true;} e.setDropCompleted(ok); e.consume(); });
        classTree.setShowRoot(false);
        classTree.setCellFactory(view -> new TreeCell<>() {
            @Override protected void updateItem(BrowserNode node, boolean empty) {
                super.updateItem(node, empty);
                if(empty || node==null){setText(null);setGraphic(null);setTooltip(null);}
                else if(node.isPackage()) { setText(node.name()); setGraphic(null); setStyle("-fx-text-fill:"+TEXT+";-fx-font-weight:bold;-fx-padding:5 4;"); setTooltip(null); }
                else {
                    Label className=new Label(node.name()); className.setStyle("-fx-text-fill:"+TEXT+";-fx-font-size:12px;");
                    Label meta=new Label(node.classInfo().methodCount()+" methods  ·  "+node.classInfo().fieldCount()+" fields"); meta.setStyle("-fx-text-fill:"+MUTED+";-fx-font-size:10px;");
                    VBox graphic=new VBox(2,className,meta); graphic.setPadding(new Insets(3,0,3,0)); setText(null);setGraphic(graphic);setStyle("-fx-padding:2 6;");setTooltip(new Tooltip(node.classInfo().displayName()));
                }
            }
        });
        search.textProperty().addListener((o,a,b)->{ if(current!=null) showTab(); });
        classTree.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null&&b.getValue()!=null&&b.getValue().classInfo()!=null) showClass(b.getValue().classInfo());});
        details.setEditable(false); details.setWrapText(false); details.setStyle(textAreaStyle());
        classDetails.setEditable(false); classDetails.setWrapText(false); classDetails.setStyle(textAreaStyle());
        sourceView.setEditable(false); sourceView.setWrapText(false); sourceView.setStyle(textAreaStyle()+"-fx-font-family:'Consolas','Cascadia Code',monospace;-fx-font-size:13px;");
        stringsView.setEditable(false); stringsView.setWrapText(false); stringsView.setStyle(textAreaStyle());
        dependenciesView.setEditable(false); dependenciesView.setWrapText(false); dependenciesView.setStyle(textAreaStyle());
        bytecodeView.setEditable(false); bytecodeView.setWrapText(false); bytecodeView.setStyle(textAreaStyle()+"-fx-font-family:monospace;-fx-font-size:12px;");
        classTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        classTabs.getTabs().setAll(new Tab("Source",sourceView),new Tab("Bytecode",bytecodeView),new Tab("Structure",classDetails),new Tab("Strings",stringsView),new Tab("References",dependenciesView));
        setupResourceTable();
        showWelcome();
    }
    private HBox windowTitlebar(Stage stage){
        Label mark=new Label("HB");mark.setStyle("-fx-background-color:"+PURPLE+";-fx-text-fill:#171323;-fx-font-size:10px;-fx-font-weight:bold;-fx-padding:4 6;-fx-background-radius:5;");
        Label name=new Label("HerlysByte");name.setStyle("-fx-text-fill:"+TEXT+";-fx-font-size:12px;-fx-font-weight:bold;");
        Label subtitle=new Label("Java Bytecode Analyzer");subtitle.setStyle("-fx-text-fill:"+MUTED+";-fx-font-size:11px;");
        HBox identity=new HBox(8,mark,name,subtitle);identity.setAlignment(Pos.CENTER_LEFT);identity.setPadding(new Insets(0,0,0,12));
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        Button minimize=windowButton("—","Minimize");minimize.setOnAction(e->stage.setIconified(true));
        Button maximize=windowButton("□","Maximize");maximize.setOnAction(e->toggleMaximize(stage));
        stage.maximizedProperty().addListener((o,wasMaximized,isMaximized)->{maximize.setText(isMaximized?"❐":"□");maximize.setAccessibleText(isMaximized?"Restore":"Maximize");});
        Button close=windowButton("×","Close");close.setStyle(windowButtonStyle(true));close.setOnAction(e->stage.close());
        HBox controls=new HBox(0,minimize,maximize,close);controls.setAlignment(Pos.CENTER_RIGHT);
        HBox bar=new HBox(identity,spacer,controls);bar.setAlignment(Pos.CENTER_LEFT);bar.setPrefHeight(38);bar.setMinHeight(38);bar.setMaxHeight(38);
        bar.setStyle("-fx-background-color:#17191f;-fx-border-color:transparent transparent "+BORDER+" transparent;");
        bar.setOnMousePressed(e->{if(e.getButton()!=javafx.scene.input.MouseButton.PRIMARY||isWindowButton(e.getTarget()))return;dragOffsetX=e.getScreenX()-stage.getX();dragOffsetY=e.getScreenY()-stage.getY();});
        bar.setOnMouseDragged(e->{if(e.getButton()==javafx.scene.input.MouseButton.PRIMARY&&!stage.isMaximized()&&!isWindowButton(e.getTarget())){stage.setX(e.getScreenX()-dragOffsetX);stage.setY(e.getScreenY()-dragOffsetY);}});
        bar.setOnMouseClicked(e->{if(e.getButton()==javafx.scene.input.MouseButton.PRIMARY&&e.getClickCount()==2&&!isWindowButton(e.getTarget()))toggleMaximize(stage);});
        return bar;
    }
    private boolean isWindowButton(Object target){javafx.scene.Node node=target instanceof javafx.scene.Node n?n:null;while(node!=null){if(node.getStyleClass().contains("window-control"))return true;node=node.getParent();}return false;}
    private String windowButtonStyle(boolean close){return "-fx-background-color:transparent;-fx-text-fill:"+(close?"#e6e8ed":MUTED)+";-fx-font-size:14px;-fx-padding:0;-fx-min-width:48px;-fx-pref-width:48px;-fx-min-height:37px;-fx-background-radius:0;";}
    private Button windowButton(String glyph,String accessible){Button b=new Button(glyph);b.getStyleClass().add("window-control");b.setAccessibleText(accessible);b.setStyle(windowButtonStyle(false));b.setOnMouseEntered(e->b.setStyle(windowButtonStyle(false)+"-fx-background-color:#292c34;"));b.setOnMouseExited(e->b.setStyle(windowButtonStyle(false)));return b;}
    private void toggleMaximize(Stage stage){stage.setMaximized(!stage.isMaximized());}
    private void installWindowResize(Scene scene,Stage stage){
        scene.setOnMouseMoved(e->{if(stage.isMaximized())return;int edges=windowEdges(e.getX(),e.getY(),scene.getWidth(),scene.getHeight());scene.setCursor(cursorFor(edges));});
        scene.setOnMousePressed(e->{if(e.getButton()!=javafx.scene.input.MouseButton.PRIMARY||stage.isMaximized())return;resizeEdges=windowEdges(e.getX(),e.getY(),scene.getWidth(),scene.getHeight());if(resizeEdges!=0){resizeStartX=e.getScreenX();resizeStartY=e.getScreenY();resizeStartWidth=stage.getWidth();resizeStartHeight=stage.getHeight();e.consume();}});
        scene.setOnMouseDragged(e->{if(resizeEdges==0||stage.isMaximized())return;double dx=e.getScreenX()-resizeStartX,dy=e.getScreenY()-resizeStartY;double x=stage.getX(),y=stage.getY(),w=resizeStartWidth,h=resizeStartHeight;
            if((resizeEdges&EDGE_RIGHT)!=0)w=Math.max(stage.getMinWidth(),resizeStartWidth+dx);
            if((resizeEdges&EDGE_BOTTOM)!=0)h=Math.max(stage.getMinHeight(),resizeStartHeight+dy);
            if((resizeEdges&EDGE_LEFT)!=0){double next=Math.max(stage.getMinWidth(),resizeStartWidth-dx);x+=resizeStartWidth-next;w=next;}
            if((resizeEdges&EDGE_TOP)!=0){double next=Math.max(stage.getMinHeight(),resizeStartHeight-dy);y+=resizeStartHeight-next;h=next;}
            if((resizeEdges&EDGE_LEFT)!=0)stage.setX(x);if((resizeEdges&EDGE_TOP)!=0)stage.setY(y);stage.setWidth(w);stage.setHeight(h);e.consume();});
        scene.setOnMouseReleased(e->resizeEdges=0);
    }
    private int windowEdges(double x,double y,double width,double height){int edges=0;double edge=6;if(x<edge)edges|=EDGE_LEFT;else if(x>width-edge)edges|=EDGE_RIGHT;if(y<edge)edges|=EDGE_TOP;else if(y>height-edge)edges|=EDGE_BOTTOM;return edges;}
    private javafx.scene.Cursor cursorFor(int edges){return switch(edges){case EDGE_LEFT,EDGE_RIGHT->javafx.scene.Cursor.H_RESIZE;case EDGE_TOP,EDGE_BOTTOM->javafx.scene.Cursor.V_RESIZE;case EDGE_TOP|EDGE_LEFT,EDGE_BOTTOM|EDGE_RIGHT->javafx.scene.Cursor.NW_RESIZE;case EDGE_TOP|EDGE_RIGHT,EDGE_BOTTOM|EDGE_LEFT->javafx.scene.Cursor.NE_RESIZE;default->javafx.scene.Cursor.DEFAULT;};}
    private HBox topbar(Stage stage) {
        Label brand = new Label("HerlysByte"); brand.setStyle("-fx-text-fill:"+TEXT+";-fx-font-size:18px;-fx-font-weight:bold;");
        Label mark = new Label("HB"); mark.setStyle("-fx-background-color:"+PURPLE+";-fx-text-fill:#171323;-fx-font-weight:bold;-fx-padding:7 9;-fx-background-radius:8;");
        Button open = button("＋  Mở JAR", true); open.setOnAction(e -> choose(stage));
        copySourceButton=button("Copy source",false);copySourceButton.setDisable(true);copySourceButton.setOnAction(e->copySource());
        exportSourceButton=button("Export .java",false);exportSourceButton.setDisable(true);exportSourceButton.setOnAction(e->saveSource());
        Button about = button("Giới thiệu", false); about.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,"HerlysByte\nPhân tích JAR bằng ASM, xem bytecode và decompile bằng CFR. Nội dung trong JAR không được thực thi.",ButtonType.OK).showAndWait());
        HBox left = new HBox(10,mark,brand); left.setAlignment(Pos.CENTER_LEFT); HBox right = new HBox(8,about,copySourceButton,exportSourceButton,open); right.setAlignment(Pos.CENTER_RIGHT);
        Region spacer=new Region(); HBox.setHgrow(spacer,Priority.ALWAYS); HBox bar=new HBox(left,spacer,right); bar.setPadding(new Insets(14,22,14,22)); bar.setStyle("-fx-background-color:"+PANEL+";-fx-border-color:transparent transparent "+BORDER+" transparent;"); return bar;
    }
    private VBox sidebar() {
        VBox side=new VBox(8); side.setPadding(new Insets(20,12,20,12)); side.setPrefWidth(210); side.setStyle("-fx-background-color:"+PANEL+";-fx-border-color:transparent "+BORDER+" transparent transparent;");
        Label section=label("WORKSPACE",MUTED,10); section.setPadding(new Insets(0,0,8,10)); side.getChildren().add(section);
        for(String item:new String[]{"Tổng quan","Classes","Resources","Strings","Dependencies","Manifest"}) { Button b=button(item,false); b.setMaxWidth(Double.MAX_VALUE); b.setAlignment(Pos.CENTER_LEFT); navButtons.put(item,b); b.setOnAction(e->{activeTab=item; updateNavigation(); showTab();}); side.getChildren().add(b); }
        Region spacer=new Region(); VBox.setVgrow(spacer,Priority.ALWAYS); Label foot=label("BYTECODE WORKBENCH",MUTED,9); side.getChildren().addAll(spacer,foot); return side;
    }
    private VBox content() {
        VBox area=new VBox(16); area.setPadding(new Insets(26,28,22,28));
        VBox headingText=new VBox(5,title,stats); Region sp=new Region(); HBox.setHgrow(sp,Priority.ALWAYS);
        search.setPromptText("Tìm class, method hoặc chuỗi…"); search.setPrefWidth(320); search.setStyle(fieldStyle()); area.getChildren().addAll(new HBox(headingText,sp,search), cardPane()); VBox.setVgrow(area.getChildren().get(1),Priority.ALWAYS); return area;
    }
    private StackPane cardPane(){ workArea.setStyle("-fx-background-color:"+PANEL+";-fx-background-radius:12;-fx-border-color:"+BORDER+";-fx-border-radius:12;"); workArea.getChildren().setAll(details); return workArea; }
    private HBox statusbar(){ status.setStyle("-fx-text-fill:"+MUTED+";-fx-font-size:11px;");activity.setMaxSize(16,16);activity.setVisible(false);HBox box=new HBox(9,activity,status);box.setAlignment(Pos.CENTER_LEFT);box.setPadding(new Insets(9,20,9,20)); box.setStyle("-fx-background-color:"+PANEL+";-fx-border-color:"+BORDER+" transparent transparent transparent;"); return box; }
    private Button button(String text,boolean primary){Button b=new Button(text);b.setStyle(primary?"-fx-background-color:"+PURPLE+";-fx-text-fill:#171323;-fx-font-weight:bold;-fx-background-radius:7;-fx-padding:9 14;":"-fx-background-color:transparent;-fx-text-fill:"+MUTED+";-fx-background-radius:7;-fx-padding:9 12;");return b;}
    private void updateNavigation(){navButtons.forEach((name,b)->b.setStyle(name.equals(activeTab)?"-fx-background-color:#292434;-fx-text-fill:"+PURPLE+";-fx-font-weight:bold;-fx-background-radius:7;-fx-padding:10 12;":"-fx-background-color:transparent;-fx-text-fill:"+MUTED+";-fx-background-radius:7;-fx-padding:10 12;"));}
    private Label label(String s,String color,int size){Label l=new Label(s);l.setStyle("-fx-text-fill:"+color+";-fx-font-size:"+size+"px;");return l;}
    private String fieldStyle(){return "-fx-background-color:"+BG+";-fx-text-fill:"+TEXT+";-fx-prompt-text-fill:"+MUTED+";-fx-border-color:"+BORDER+";-fx-border-radius:7;-fx-background-radius:7;-fx-padding:9;";}
    private String textAreaStyle(){return "-fx-control-inner-background:"+PANEL+";-fx-text-fill:"+TEXT+";-fx-font-family:monospace;-fx-font-size:12px;-fx-highlight-fill:#514080;";}
    private void choose(Stage stage){FileChooser c=new FileChooser();c.setTitle("Chọn JAR cần phân tích");c.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java Archive (*.jar)","*.jar"));File f=c.showOpenDialog(stage);if(f!=null)open(f.toPath());}
    private void setupResourceTable(){
        TableColumn<JarResourceInfo,String> name=new TableColumn<>("Resource path");name.setCellValueFactory(v->new javafx.beans.property.SimpleStringProperty(v.getValue().name()));name.setPrefWidth(620);
        TableColumn<JarResourceInfo,String> size=new TableColumn<>("Size");size.setCellValueFactory(v->new javafx.beans.property.SimpleStringProperty(formatSize(v.getValue().size())));size.setPrefWidth(110);
        resourceTable.getColumns().setAll(name,size);resourceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);resourceTable.setPlaceholder(label("No resources match this filter.",MUTED,12));
        Button extract=button("Extract selected",true);extract.disableProperty().bind(resourceTable.getSelectionModel().selectedItemProperty().isNull());extract.setOnAction(e->exportSelectedResource());
        Label hint=label("Select a file inside the archive, then extract it to disk.",MUTED,11);HBox footer=new HBox(hint, new Region(),extract);HBox.setHgrow(footer.getChildren().get(1),Priority.ALWAYS);footer.setAlignment(Pos.CENTER_LEFT);footer.setPadding(new Insets(10,14,10,14));
        resourcesPane.setTop(label("  RESOURCES",MUTED,11));resourcesPane.setCenter(resourceTable);resourcesPane.setBottom(footer);
    }
    private void exportSelectedResource(){
        JarResourceInfo selected=resourceTable.getSelectionModel().getSelectedItem();if(selected==null||current==null)return;
        FileChooser chooser=new FileChooser();chooser.setTitle("Extract resource");String fileName=Path.of(selected.name()).getFileName().toString();chooser.setInitialFileName(fileName);File target=chooser.showSaveDialog(primaryStage);if(target==null)return;
        try(java.util.jar.JarFile jar=new java.util.jar.JarFile(current.file().toFile());var in=jar.getInputStream(jar.getJarEntry(selected.name()))){Files.copy(in,target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);status.setText("Extracted "+selected.name());}
        catch(Exception ex){new Alert(Alert.AlertType.ERROR,"Could not extract resource.\n"+ex.getMessage(),ButtonType.OK).showAndWait();}
    }
    private void saveSource(){if(currentSource.isBlank())return;FileChooser chooser=new FileChooser();chooser.setTitle("Save decompiled Java source");ClassInfo selected=selectedBrowserClass();chooser.setInitialFileName((selected==null?"Decompiled":selected.name().replace('/','_'))+".java");File target=chooser.showSaveDialog(primaryStage);if(target==null)return;try{Files.writeString(target.toPath(),currentSource);status.setText("Saved source to "+target.getName());}catch(Exception ex){new Alert(Alert.AlertType.ERROR,"Could not save source.\n"+ex.getMessage(),ButtonType.OK).showAndWait();}}
    private void copySource(){if(currentSource.isBlank())return;ClipboardContent content=new ClipboardContent();content.putString(currentSource);Clipboard.getSystemClipboard().setContent(content);status.setText("Decompiled source copied to clipboard.");}
    private void handleShortcut(KeyEvent e){if(!e.isControlDown())return;if(e.getCode()==KeyCode.O){choose(primaryStage);e.consume();}else if(e.getCode()==KeyCode.F){search.requestFocus();search.selectAll();e.consume();}else if(e.getCode()==KeyCode.S){saveSource();e.consume();}else if(e.getCode()==KeyCode.C&&e.isShiftDown()){copySource();e.consume();}}
    private void open(Path path){
        if(activeAnalysisTask!=null)activeAnalysisTask.cancel(true);
        if(activeClassTask!=null)activeClassTask.cancel(true);
        long request=analysisGeneration.incrementAndGet();activity.setVisible(true);status.setText("Analyzing "+path.getFileName()+"…");
        activeAnalysisTask=worker.submit(()->{
            try {
                JarAnalysis result=analyzer.analyze(path);
                Platform.runLater(()->{
                    if(request!=analysisGeneration.get())return;
                    current=result;currentSource="";copySourceButton.setDisable(true);exportSourceButton.setDisable(true);
                    activeTab="Tổng quan";updateNavigation();search.clear();title.setText(path.getFileName().toString());
                    stats.setText(result.classes().size()+" classes  ·  "+result.methodCount()+" methods  ·  "+formatSize(result.fileSize()));
                    status.setText("Analysis complete · "+result.resourceCount()+" resources"+(result.warnings().isEmpty()?"":" · "+result.warnings().size()+" warnings"));activity.setVisible(false);showTab();
                });
            } catch(Exception ex){
                Platform.runLater(()->{if(request!=analysisGeneration.get())return;activity.setVisible(false);status.setText("Could not analyze archive");new Alert(Alert.AlertType.ERROR,"Could not analyze JAR\n\n"+ex.getMessage(),ButtonType.OK).showAndWait();});
            }
        });
    }
    private void showWelcome(){showText("HERLYSBYTE  /  JAVA BYTECODE ANALYZER\n\nMở một tệp JAR để xem tổng quan cấu trúc, class, method, chuỗi và dependencies.\n\n• Kéo thả .jar vào cửa sổ hoặc nhấn “Mở JAR”\n• Tìm kiếm class, method và string constants\n• Tệp trong archive không được nạp hay thực thi\n\nPhiên bản 1.0.0 · ASM");}
    private void showText(String value){details.setText(value);workArea.getChildren().setAll(details);}
    private void showTab(){if(current==null){showWelcome();return;} switch(activeTab){case "Classes" -> showClassesView();case "Resources" -> showResources();case "Strings" -> showStrings();case "Dependencies" -> showDependencies();case "Manifest" -> showManifest();default -> showOverview();}}
    private void showOverview(){StringBuilder s=new StringBuilder("JAR OVERVIEW\n\n");s.append("File       ").append(current.file().getFileName()).append("\nSize       ").append(formatSize(current.fileSize())).append("\nClasses    ").append(current.classes().size()).append("\nMethods    ").append(current.methodCount()).append("\nFields     ").append(current.fieldCount()).append("\nResources  ").append(current.resourceCount()).append("\nMain class ").append(current.mainClass()).append("\n\nPACKAGES\n");current.classes().stream().map(ClassInfo::packageName).distinct().sorted().forEach(p->s.append("  ").append(p).append("\n"));if(!current.warnings().isEmpty()){s.append("\nWARNINGS\n");current.warnings().forEach(w->s.append("  ! ").append(w).append("\n"));}showText(s.toString());}
    private void showClassesView(){
        String q=search.getText().trim().toLowerCase(Locale.ROOT);
        ClassInfo selected=selectedBrowserClass();
        var filtered=current.classes().stream().filter(c->q.isEmpty()||c.displayName().toLowerCase(Locale.ROOT).contains(q)||c.methods().stream().anyMatch(m->m.toLowerCase(Locale.ROOT).contains(q))||c.strings().stream().anyMatch(v->v.toLowerCase(Locale.ROOT).contains(q))).toList();
        TreeItem<BrowserNode> rootItem=new TreeItem<>(new BrowserNode("JAR",null));
        java.util.Map<String,TreeItem<BrowserNode>> folders=new java.util.LinkedHashMap<>(); folders.put("",rootItem);
        for(ClassInfo c:filtered){
            String pkg=c.packageName(); TreeItem<BrowserNode> parent=rootItem;
            if(!pkg.equals("(default package)")){
                StringBuilder path=new StringBuilder();
                for(String part:pkg.split("\\.")){
                    if(path.length()>0)path.append('.'); path.append(part);
                    String key=path.toString(); TreeItem<BrowserNode> folder=folders.get(key);
                    if(folder==null){folder=new TreeItem<>(new BrowserNode(part,null));folders.put(key,folder);parent.getChildren().add(folder);}
                    parent=folder;
                }
            }
            String name=c.displayName(); int dot=name.lastIndexOf('.');
            parent.getChildren().add(new TreeItem<>(new BrowserNode(dot<0?name:name.substring(dot+1),c)));
        }
        sortTree(rootItem);
        rootItem.setExpanded(true);
        classTree.setRoot(rootItem);
        classTree.setStyle("-fx-background-color:#15171d;-fx-border-color:transparent transparent transparent "+BORDER+";");
        classTree.setPrefWidth(330); classTree.setMinWidth(230);
        Label listTitle=label("PACKAGES  ·  "+filtered.size()+" classes",MUTED,11); listTitle.setPadding(new Insets(12,12,8,14));
        VBox left=new VBox(listTitle,classTree); left.setPrefWidth(330); VBox.setVgrow(classTree,Priority.ALWAYS);
        classView.getChildren().setAll(left,classTabs); HBox.setHgrow(classTabs,Priority.ALWAYS); classTabs.setStyle("-fx-background-color:"+PANEL+";");
        workArea.getChildren().setAll(classView);
        if(selected!=null&&filtered.contains(selected)){TreeItem<BrowserNode> item=findClass(rootItem,selected);if(item!=null){expandParents(item);classTree.getSelectionModel().select(item);}}
        else if(!filtered.isEmpty())selectFirstClass(rootItem);
        else { sourceView.setText("Không tìm thấy class phù hợp."); classDetails.clear(); stringsView.clear(); dependenciesView.clear(); }
    }
    private ClassInfo selectedBrowserClass(){TreeItem<BrowserNode> item=classTree.getSelectionModel().getSelectedItem();return item==null||item.getValue()==null?null:item.getValue().classInfo();}
    private void sortTree(TreeItem<BrowserNode> item){item.getChildren().sort(java.util.Comparator.comparing((TreeItem<BrowserNode> child)->!child.getValue().isPackage()).thenComparing(child->child.getValue().name(),String.CASE_INSENSITIVE_ORDER));for(TreeItem<BrowserNode> child:item.getChildren())if(child.getValue().isPackage())sortTree(child);}
    private TreeItem<BrowserNode> findClass(TreeItem<BrowserNode> item,ClassInfo target){if(item.getValue()!=null&&item.getValue().classInfo()==target)return item;for(TreeItem<BrowserNode> child:item.getChildren()){TreeItem<BrowserNode> found=findClass(child,target);if(found!=null)return found;}return null;}
    private void expandParents(TreeItem<BrowserNode> item){TreeItem<BrowserNode> parent=item.getParent();while(parent!=null){parent.setExpanded(true);parent=parent.getParent();}}
    private void selectFirstClass(TreeItem<BrowserNode> item){if(item.getValue()!=null&&item.getValue().classInfo()!=null){expandParents(item);classTree.getSelectionModel().select(item);return;}for(TreeItem<BrowserNode> child:item.getChildren()){selectFirstClass(child);if(selectedBrowserClass()!=null)return;}}
    private void showStrings(){String q=search.getText().toLowerCase(Locale.ROOT);StringBuilder s=new StringBuilder("STRING CONSTANTS\n\n");current.classes().forEach(c->c.strings().stream().filter(x->q.isEmpty()||x.toLowerCase(Locale.ROOT).contains(q)).forEach(x->s.append(c.displayName()).append("  →  ").append(x.replace("\n","\\n")).append("\n")));showText(s.toString());}
    private void showDependencies(){String q=search.getText().toLowerCase(Locale.ROOT);StringBuilder s=new StringBuilder("CLASS DEPENDENCIES (observed references)\n\n");current.classes().forEach(c->c.dependencies().stream().filter(x->q.isEmpty()||x.toLowerCase(Locale.ROOT).contains(q)).forEach(x->s.append(c.displayName()).append("  →  ").append(x).append("\n")));showText(s.toString());}
    private void showManifest(){StringBuilder s=new StringBuilder("MANIFEST.MF\n\n");if(current.manifest().isEmpty())s.append("Không có manifest trong JAR.");else current.manifest().forEach((k,v)->s.append(k).append(": ").append(v).append("\n"));showText(s.toString());}
    private void showResources(){String q=search.getText().trim().toLowerCase(Locale.ROOT);var filtered=current.resources().stream().filter(r->q.isEmpty()||r.name().toLowerCase(Locale.ROOT).contains(q)).toList();resourceTable.getItems().setAll(filtered);resourcesPane.setStyle("-fx-background-color:"+PANEL+";");workArea.getChildren().setAll(resourcesPane);}
    private void showClass(ClassInfo c){
        if(current==null)return;
        activeTab="Classes";
        if(activeClassTask!=null)activeClassTask.cancel(true);
        currentSource="";copySourceButton.setDisable(true);exportSourceButton.setDisable(true);activity.setVisible(true);
        StringBuilder metadata=new StringBuilder("CLASS\n\n").append(c.displayName()).append("\n\nPackage: ").append(c.packageName()).append("\nSuperclass: ").append(c.superName()).append("\nInterfaces: ").append(String.join(", ",c.interfaces())).append("\nClass file version: ").append(c.majorVersion()).append("\nFields: ").append(c.fieldCount()).append("\nMethods: ").append(c.methodCount()).append("\n\nMETHOD SIGNATURES\n");
        c.methods().forEach(m->metadata.append("  ").append(m).append("\n"));
        classDetails.setText(metadata.toString());
        stringsView.setText(c.strings().isEmpty()?"No string constants found.":String.join("\n",c.strings()));
        dependenciesView.setText(c.dependencies().isEmpty()?"No referenced classes found.":String.join("\n",c.dependencies()));
        bytecodeView.setText("Disassembling "+c.displayName()+"…");
        sourceView.setText("// Decompiling "+c.displayName()+" …\n// CFR converts bytecode back into readable Java source.");
        classTabs.getSelectionModel().select(0);
        JarAnalysis selectedJar=current;
        status.setText("Decompiling "+c.displayName()+"…");
        activeClassTask=classWorker.submit(()->{
            String javaSource;
            String bytecode;
            try {
                javaSource=decompiler.decompile(selectedJar.file(),c.name());
            } catch(Exception ex) {
                javaSource="// Could not decompile this class.\n// "+ex.getMessage();
            }
            try { bytecode=bytecodeDisassembler.disassemble(selectedJar,c); }
            catch(Exception ex){bytecode="// Could not disassemble this class.\n// "+ex.getMessage();}
            String finalSource=javaSource,finalBytecode=bytecode;
            Platform.runLater(()->{
                if(current==selectedJar&&selectedBrowserClass()==c){currentSource=finalSource.startsWith("// Could not decompile")?"":finalSource;sourceView.setText(finalSource);bytecodeView.setText(finalBytecode);copySourceButton.setDisable(currentSource.isBlank());exportSourceButton.setDisable(currentSource.isBlank());activity.setVisible(false);status.setText("Ready · "+c.displayName());}
            });
        });
    }
    private String formatSize(long n){if(n<1024)return n+" B";return String.format(Locale.ROOT,"%.1f KB",n/1024.0);}
    @Override public void stop(){worker.shutdownNow();classWorker.shutdownNow();}
    public static void main(String[] args){launch(args);}
}
