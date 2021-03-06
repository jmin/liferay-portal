AUI.add(
	'liferay-so-invite-members',
	function(A) {
		var InviteMembers = function() {
			InviteMembers.superclass.constructor.apply(this, arguments);
		};

		InviteMembers.NAME = 'soinvitemembers';

		InviteMembers.ATTRS = {
			portletNamespace: {
				value: ''
			}
		};

		A.extend(
			InviteMembers,
			A.Base,
			{
				initializer: function(params) {
					var instance = this;

					instance._inviteMembersContainer = A.one('#' + instance.get('portletNamespace') + 'inviteMembersContainer');

					if (!instance._inviteMembersContainer) {
						return;
					}

					instance._emailButton = instance._inviteMembersContainer.one('#' + instance.get('portletNamespace') + 'emailButton');
					instance._emailInput = instance._inviteMembersContainer.one('#' + instance.get('portletNamespace') + 'emailAddress');
					instance._membersList = instance._inviteMembersContainer.one('#' + instance.get('portletNamespace') + 'membersList');
					instance._invitedEmailList = instance._inviteMembersContainer.one('#' + instance.get('portletNamespace') + 'invitedEmailList');
					instance._invitedMembersList = instance._inviteMembersContainer.one('#' + instance.get('portletNamespace') + 'invitedMembersList');

					var form = instance._inviteMembersContainer.one('#' + instance.get('portletNamespace') + 'fm');

					form.on(
						'submit',
						function(event) {
							instance._syncFields(form);
						}
					);

					instance._emailButton.on(
						'click',
						function(event) {
							instance._addMemberEmail();

							Liferay.Util.focusFormField(instance._emailInput.getDOM());
						}
					);

					instance._inviteMembersContainer.delegate(
						'click',
						function(event) {
							var user = event.currentTarget;

							var userEmail = user.attr('data-emailAddress');
							var userId = user.attr('data-userId');

							if (userId) {
								if (user.hasClass('invited')) {
									instance._removeMemberInvite(user, userId);
								}
								else {
									instance._addMemberInvite(user);
								}
							}
							else {
								instance._removeEmailInvite(user);
							}
						},
						'.user'
					);

					instance._inviteMembersContainer.delegate(
						'keyup',
						function(event) {
							if (event.keyCode == 13) {
								instance._addMemberEmail();
							}
						},
						'.controls'
					);
				},

				_addMemberEmail: function() {
					var instance = this;

					var emailAddress = A.Lang.trim(instance._emailInput.val());

					if (emailAddress) {
						var html = '<div class="user" data-emailAddress="' + emailAddress + '"><span class="email">' + emailAddress + '</span></div>';

						instance._invitedEmailList.append(html);
					}

					instance._emailInput.val('');
				},

				_addMemberInvite: function(user) {
					var instance = this;

					user.addClass('invited').cloneNode(true).appendTo(instance._invitedMembersList);
				},

				_removeEmailInvite: function(user) {
					user.remove();
				},

				_removeMemberInvite: function(user, userId) {
					var instance = this;

					userId = userId || user.getAttribute('data-userId');

					var user = instance._membersList.one('[data-userId="' + userId + '"]');

					if (user) {
						user.removeClass('invited');
					}

					var invitedUser = instance._invitedMembersList.one('[data-userId="' + userId + '"]');

					invitedUser.remove();
				},

				_syncFields: function(form) {
					var instance = this;

					var userIds = [];
					var emailAddresses = [];

					instance._invitedMembersList.all('.user').each(
						function(item, index) {
							userIds.push(item.attr('data-userId'));
						}
					);

					instance._invitedEmailList.all('.user').each(
						function(item, index) {
							emailAddresses.push(item.attr('data-emailAddress'));
						}
					);

					var role = instance._inviteMembersContainer.one('select[name=' + instance.get('portletNamespace') + 'roleId]');
					var team = instance._inviteMembersContainer.one('select[name=' + instance.get('portletNamespace') + 'teamId]');

					form.one('input[name="' + instance.get('portletNamespace') + 'receiverUserIds"]').val(userIds.join());
					form.one('input[name="' + instance.get('portletNamespace') + 'receiverEmailAddresses"]').val(emailAddresses.join());
					form.one('input[name="' + instance.get('portletNamespace') + 'invitedRoleId"]').val(role ? role.val() : 0);
					form.one('input[name="' + instance.get('portletNamespace') + 'invitedTeamId"]').val(team ? team.val() : 0);
				}
			}
		);

		Liferay.namespace('SO');

		Liferay.SO.InviteMembers = InviteMembers;
	},
	'',
	{
		requires: ['aui-base', 'aui-io-deprecated', 'liferay-util-window']
	}
);

AUI.add(
	'liferay-so-invite-members-list',
	function(A) {
		var InviteMembersList = A.Base.create(
			'inviteMembersList',
			A.Base,
			[A.AutoCompleteBase],
			{
				initializer: function(config) {
					this._listNode = A.one(config.listNode);

					this._bindUIACBase();
					this._syncUIACBase();
				}
			}
		);

		Liferay.namespace('SO');

		Liferay.SO.InviteMembersList = InviteMembersList;
	},
	'',
	{
		requires: ['aui-base', 'autocomplete-base', 'node-core']
	}
);