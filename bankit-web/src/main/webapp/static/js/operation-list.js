$(document).ready(function() {
	$('*[title]').tooltip({placement: 'bottom'});
	
	document.getElements('select.cat_select').addEvent('change', saveCategory);
});

/**
 * Call the ajax to save the category for an operation
 */
function saveCategory() {
	var cat_id = this.get('value'),
		op_id = this.get('id').substring(4);
	
	$loading(true);
	
	new Request.JSON({
		url: $ctx_path + 'account/update_cat.json',
		onSuccess: saveCategoryDisp.bind(this),
		onFailure: saveCategoryDisp.bind(this)
	}).post({cat: cat_id, op: op_id});
}

/**
 * Display the return saveCategory ajax call
 * @param res Return from the server
 */
function saveCategoryDisp(res) {
	$loading(false);
	
	if (res.isOk) {
		showConfirm("Enregistré");
		//TODO do something better to update Category summary
		document.location.reload();
	} else {
		showError(res.errorName ? res.errorName :"Impossible de mettre à jour la catégorie");
		this.selectedIndex = 0;
	}
}