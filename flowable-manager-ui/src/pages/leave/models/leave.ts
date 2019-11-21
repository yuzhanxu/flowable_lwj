import { Effect } from 'dva';
import { Reducer } from 'redux';
import { message } from 'antd';

import { queryLeave, insertLeave, updateLeave } from '@/pages/leave/services/leave';

export interface CurrentUser {
  modules?: Array;
  avatar?: string;
  name?: string;
  title?: string;
  group?: string;
  signature?: string;
  tags?: {
    key: string;
    label: string;
  }[];
  userid?: string;
  unreadCount?: number;
}

export interface UserModelState {
  currentUser?: CurrentUser;
}

export interface LeaveModelType {
  namespace: 'modules';
  state: UserModelState;
  effects: {
    fetch: Effect;
    fetchCurrent: Effect;
  };
  reducers: {
    saveCurrentUser: Reducer<UserModelState>;
    changeNotifyCount: Reducer<UserModelState>;
  };
}

const LeaveModel: LeaveModelType = {
  namespace: 'leave',
  state: {
    currentUser: {},
  },

  effects: {
    *fetch(_, { call, put }) {
      const response = yield call(queryLeave);
      yield put({
        type: 'list',
        payload: response,
      });
    },
    *insert({ payload }, { call, put }) {
      const response = yield call(insertLeave, payload);
      if (response.code === '100') {
        message.success(response.msg);
        yield put({
          type: 'fetch',
          payload: {},
        });
      } else {
        message.error(response.msg);
      }
    },
  },

  reducers: {
    list(state, action) {
      return {
        ...state,
        modules: action.payload.data.data || [],
      };
    },
    saveLeave(state, action) {
      return {
        ...state,
        currentUser:
          { userid: action.payload.id, name: action.payload.firstName, ...action.payload } || {},
      };
    },
    changeNotifyCount(
      state = {
        currentUser: {},
      },
      action,
    ) {
      return {
        ...state,
        currentUser: {
          ...state.currentUser,
          notifyCount: action.payload.totalCount,
          unreadCount: action.payload.unreadCount,
        },
      };
    },
  },
};

export default LeaveModel;